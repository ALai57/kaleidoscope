(ns kaleidoscope.tasks.reconcile
  "Offline reconciliation / reclamation for the media object store.

  The dangerous part — deciding which bytes move toward deletion by diffing the
  source of truth against stored objects — lives here as a PURE, TOTAL,
  unit-tested core (`reconcile-plan`), NOT in bash where one field-split surprise
  quarantines a live photo. The effectful driver only supplies the sets (from an
  S3 Inventory export + a DB query) and executes the plan when the gate is `:ok`.

  Append-only store: deleting a photo drops its row and leaves the blob (an
  orphan). Periodically: orphans = stored - referenced (quarantined to `trash/`,
  later lifecycle-expired; never hard-deleted), dangling = referenced - stored
  (alert: possible data loss), mismatched = rows whose content_hash disagrees
  with the stored object's checksum (integrity alert — the present reader that
  earns the content_hash column). Gated: refuse if the referenced set shrank
  suspiciously or the index is unhealthy; reversible via the trash/ prefix +
  bucket versioning; NEVER run against a corrupted index (restore from PITR
  first — see docs/operations.md)."
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [kaleidoscope.api.albums :as albums]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure core — no I/O, fully unit-tested over in-memory sets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def TRASH-PREFIX
  "Quarantine destination prefix. Orphans are copied here (then the original
  deleted) rather than hard-deleted, so bucket versioning + this prefix make
  reclamation reversible."
  "trash/")

(def SHRINK-ABORT-THRESHOLD
  "Refuse to quarantine if the referenced set shrank to below this fraction of
  the previous run's count — a suspicious drop signals a broken/rolled-back
  index, not real churn."
  0.9)

(defn quarantine-key
  "Where an orphan is moved instead of being hard-deleted."
  [k]
  (str TRASH-PREFIX k))

(defn derivable-keys
  "Reduce the live `photo_versions` rows to the set of keys the index references
  and the content_hash for each row that carries one. Pure — the driver supplies
  the rows. Rows with a blank/nil content_hash (the pre-Phase-3 corpus) simply
  contribute no hash entry, so reconciliation skips their integrity check."
  [rows]
  (reduce (fn [acc {:keys [path content-hash]}]
            (cond-> (update acc :referenced conj path)
              (not (string/blank? content-hash)) (assoc-in [:hashes path] content-hash)))
          {:referenced #{} :hashes {}}
          rows))

(defn reconcile-plan
  "Pure, total reconciliation plan.

  Input:
    :stored                a set of keys present in the store (S3 Inventory)
    :referenced            a set of keys derivable from live rows
    :hashes                {key -> content_hash} for rows that carry one
    :object-hashes         {key -> actual checksum} the driver re-computed
    :last-referenced-count previous run's referenced count (for the shrink gate)
    :index-healthy?        driver's DB health verdict (default true)

  Output:
    :orphans     stored - referenced (the quarantine action set; EMPTY when gated)
    :dangling    referenced - stored (alert only; always reported)
    :mismatched  keys whose stored checksum disagrees with content_hash (alert)
    :gate        :ok | :abort-shrink | :abort-unhealthy"
  [{:keys [stored referenced hashes object-hashes last-referenced-count index-healthy?]
    :or   {hashes {} object-hashes {} index-healthy? true}}]
  (let [stored     (set stored)
        referenced (set referenced)
        orphans    (set/difference stored referenced)
        dangling   (set/difference referenced stored)
        present    (set/intersection stored referenced)
        mismatched (into #{}
                         (filter (fn [k]
                                   (let [expected (get hashes k)
                                         actual   (get object-hashes k)]
                                     (and expected actual (not= expected actual)))))
                         present)
        shrank?    (boolean (and last-referenced-count
                                 (pos? last-referenced-count)
                                 (< (count referenced)
                                    (* SHRINK-ABORT-THRESHOLD last-referenced-count))))
        gate       (cond
                     (not index-healthy?) :abort-unhealthy
                     shrank?              :abort-shrink
                     :else                :ok)]
    {:orphans    (if (= gate :ok) orphans #{})   ;; never quarantine on a suspicious/unhealthy index
     :dangling   dangling
     :mismatched mismatched
     :gate       gate}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Effectful driver — thin glue around the pure core (not unit-tested; the
;; risky logic is above). Reads the sets, calls reconcile-plan, executes only
;; when the gate is :ok.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-stored-keys
  "The `stored` set. `path` is a newline-delimited keys file the operator
  materializes from the bucket's S3 Inventory export (or `aws s3 ls --recursive
  | awk '{print $4}'`). One key per line; blanks ignored."
  [path]
  (with-open [r (clojure.java.io/reader path)]
    (into #{} (comp (map string/trim) (remove string/blank?)) (line-seq r))))

(defn report
  "Human-readable summary of a plan (counts, not full key dumps)."
  [{:keys [orphans dangling mismatched gate]}]
  (format "gate=%s orphans=%d dangling=%d mismatched=%d" gate (count orphans) (count dangling) (count mismatched)))

(defn execute-plan!
  "Quarantine each orphan: CopyObject to trash/<key>, then DeleteObject the
  original (bucket versioning keeps even that reversible). Called ONLY when the
  gate is :ok. `s3-invoke!` is `(fn [op-map] ...)` so this stays testable in
  isolation from a live client if ever needed."
  [s3-invoke! bucket {:keys [orphans]}]
  (doseq [k orphans]
    (log/infof "Quarantining orphan %s -> %s" k (quarantine-key k))
    (s3-invoke! {:op :CopyObject :request {:Bucket bucket :CopySource (str bucket "/" k) :Key (quarantine-key k)}})
    (s3-invoke! {:op :DeleteObject :request {:Bucket bucket :Key k}})))

(defn index-healthy?
  "Cheap sanity check before any destructive action: the index connection works
  and photo_versions is non-empty. A failed check aborts the run (never reconcile
  against a corrupted index — restore from PITR first)."
  [ds]
  (try
    (let [n (-> (next/execute-one! ds ["SELECT count(*) AS c FROM photo_versions"]) vals first)]
      (boolean (and n (pos? n))))
    (catch Throwable e
      (log/error e "Index health check failed")
      false)))

(defn query-rows
  "Live photo_versions rows as {:path .. :content-hash ..}."
  [ds]
  (map (fn [row] {:path (:photo_versions/path row) :content-hash (:photo_versions/content_hash row)})
       (next/execute! ds ["SELECT path, content_hash FROM photo_versions"])))

(defn object-sha256
  "sha256:<hex> of the object at `k`, computed by GETting its bytes — the
  integrity pass that consumes content_hash. nil on any error (treated as
  no-actual-hash, so it never falsely flags a mismatch)."
  [s3-invoke! bucket k]
  (try
    (let [{:keys [Body]} (s3-invoke! {:op :GetObject :request {:Bucket bucket :Key k}})
          baos (java.io.ByteArrayOutputStream.)]
      (io/copy Body baos)
      (str "sha256:" (albums/sha256-hex (.toByteArray baos))))
    (catch Throwable e
      (log/warnf "Could not read %s for hash verification: %s" k (ex-message e))
      nil)))

(defn -main
  "Operator entry point (thin launcher calls this). Reads env-supplied config,
  assembles the sets, runs the pure `reconcile-plan`, prints the report, and —
  only when the gate is :ok and RECONCILE_APPLY=1 — quarantines orphans.

  Env: KALEIDOSCOPE_MEDIA_BUCKET, RECONCILE_STORED_KEYS (newline-delimited keys
  file), KALEIDOSCOPE_DB_* (index connection), optional
  RECONCILE_LAST_REFERENCED_COUNT, RECONCILE_VERIFY_HASHES=1, RECONCILE_APPLY=1."
  [& _args]
  (let [env      (System/getenv)
        bucket   (get env "KALEIDOSCOPE_MEDIA_BUCKET")
        keysfile (get env "RECONCILE_STORED_KEYS")
        apply?   (= "1" (get env "RECONCILE_APPLY"))
        verify?  (= "1" (get env "RECONCILE_VERIFY_HASHES"))
        last-n   (some-> (get env "RECONCILE_LAST_REFERENCED_COUNT") parse-long)]
    (when (string/blank? bucket)   (throw (ex-info "KALEIDOSCOPE_MEDIA_BUCKET not set" {})))
    (when (string/blank? keysfile) (throw (ex-info "RECONCILE_STORED_KEYS (stored-keys file) not set" {})))
    (let [ds     (next/get-datasource {:dbtype   "postgresql"
                                       :dbname   (get env "KALEIDOSCOPE_DB_NAME")
                                       :host     (get env "KALEIDOSCOPE_DB_HOST")
                                       :port     (or (some-> (get env "KALEIDOSCOPE_DB_PORT") parse-long) 5432)
                                       :user     (get env "KALEIDOSCOPE_DB_USER")
                                       :password (get env "KALEIDOSCOPE_DB_PASSWORD")})
          s3     (aws/client {:api :s3 :credentials-provider (creds/environment-credentials-provider)})
          invoke (fn [op-map]
                   (let [r (aws/invoke s3 op-map)]
                     (when (:cognitect.anomalies/category r)
                       (throw (ex-info "S3 error" r)))
                     r))
          {:keys [referenced hashes]} (derivable-keys (query-rows ds))
          object-hashes (when verify?
                          (into {} (for [k (keys hashes)] [k (object-sha256 invoke bucket k)])))
          plan   (reconcile-plan {:stored                (read-stored-keys keysfile)
                                  :referenced            referenced
                                  :hashes                hashes
                                  :object-hashes         (or object-hashes {})
                                  :last-referenced-count last-n
                                  :index-healthy?        (index-healthy? ds)})]
      (log/infof "Reconcile plan: %s" (report plan))
      (when (seq (:dangling plan))   (log/warnf "DANGLING (possible data loss): %s" (:dangling plan)))
      (when (seq (:mismatched plan)) (log/warnf "MISMATCHED (integrity): %s" (:mismatched plan)))
      (cond
        (not= :ok (:gate plan)) (log/errorf "Gate %s — refusing to quarantine. Investigate before re-running." (:gate plan))
        (not apply?)            (log/infof "Dry-run: would quarantine %d orphan(s) to %s. Set RECONCILE_APPLY=1 to apply." (count (:orphans plan)) TRASH-PREFIX)
        :else                   (do (log/infof "Applying: quarantining %d orphan(s)..." (count (:orphans plan)))
                                    (execute-plan! invoke bucket plan)))
      (log/info "Reconcile complete."))))
