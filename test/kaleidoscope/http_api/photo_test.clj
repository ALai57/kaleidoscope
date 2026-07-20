(ns kaleidoscope.http-api.photo-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.api.resize :as resize]
            [kaleidoscope.http-api.photo :as photo]
            [kaleidoscope.http-api.tenant :as http-tenant]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.utils.core :as u]
            [matcher-combinators.test :refer [match?]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]
           [java.util.concurrent CountDownLatch Semaphore TimeUnit]))

;; Verified exploitable 2026-07-03 (see PLAN.md): the previous
;; implementation (`(last (str/split path #"\."))`) returned the *entire*
;; filename when it contained no `.` at all, and could still return a
;; slash-containing tail for filenames with multiple `.`s. This value is
;; spliced directly into a storage path, so a malicious upload filename
;; could redirect where the file gets written on disk (see
;; persistence/filesystem/local.clj's confinement check, added as a second,
;; independent layer of defense against the same class of bug).
(deftest get-file-extension-test
  (testing "A normal filename extracts its extension"
    (is (= "jpg" (photo/get-file-extension "photo.jpg")))
    (is (= "jpeg" (photo/get-file-extension "my.photo.jpeg"))))

  (testing "A filename with no dot doesn't leak the whole filename as the extension"
    (is (not= "../../../../etc/cron.d/evil" (photo/get-file-extension "../../../../etc/cron.d/evil")))
    (is (= "bin" (photo/get-file-extension "../../../../etc/cron.d/evil"))))

  (testing "A filename engineered to produce a slash-containing tail is rejected, not passed through"
    (is (not (re-find #"/" (photo/get-file-extension "a.b/../../etc/passwd")))))

  (testing "An overly long or symbol-laden suffix falls back to a safe default"
    (is (= "bin" (photo/get-file-extension "file.this-is-not-a-safe-extension")))
    (is (= "bin" (photo/get-file-extension "file.")))
    (is (= "bin" (photo/get-file-extension "")))))

;; Task 10: :tenant (DB scope) and :asset-store (upload destination) diverge in
;; an ephemeral env — a "fixed" resolver pins :tenant to the real site
;; ("andrewslai.com") while :asset-store points at the isolated per-env bucket
;; ("ephemeral-tenant-assets"), even though the raw Host header is some
;; fly.dev-style ephemeral hostname. The upload must write bytes to the
;; isolated store while recording the DB photo row under the pinned tenant.
(deftest process-photo-upload-scopes-db-and-store-independently-test
  (let [database          (embedded-h2/fresh-db!)
        tenant-store      (atom {})
        ephemeral-store   (atom {})
        gate              (resize/make-resize-gate (in-mem/make-mem-fs {:store ephemeral-store}) {:max-concurrent 1})
        req               {:headers    {"host" "kal-eph-xyz.fly.dev"}
                            :tenant     {:hostname "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
                            :components {:database                database
                                         :static-content-adapters {"andrewslai.com"          (in-mem/make-mem-fs {:store tenant-store})
                                                                    "ephemeral-tenant-assets" (in-mem/make-mem-fs {:store ephemeral-store})}
                                         :resize-gate             gate}}
        file              {:filename "myfile.png"
                            :tempfile (io/file (io/resource "public/images/lock.svg"))}]
    (try
      (photo/process-photo-upload! req file)

      (testing "The photo bytes land in the isolated asset store, not the pinned tenant's own bucket"
        (is (match? {albums-api/MEDIA-FOLDER map?} @ephemeral-store))
        (is (= {} @tenant-store)))

      (testing "The DB photo row is scoped to the pinned tenant, not the raw ephemeral Host header"
        (is (seq (albums-api/get-full-photos database {:hostname "andrewslai.com"})))
        (is (empty? (albums-api/get-full-photos database {:hostname "kal-eph-xyz.fly.dev"}))))
      (finally (resize/stop! gate)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; serve-photo's heal-or-enqueue! branch — a rendition GET that 404s against
;; the media store gets one chance to self-heal instead of just failing.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord RepeatableMemFS
  ;; See kaleidoscope.api.resize-test/RepeatableMemFS: a raw read happens once
  ;; per rendition category, so a store handing back the literal, single-use
  ;; InputStream it was given at seed time (like in-memory-impl/MemFS) would
  ;; starve reads after the first. This double stores byte[] and hands back a
  ;; fresh stream on every get-file.
  [files]
  fs/DistributedFileSystem
  (ls [_ _path _options] nil)
  (get-file [_ path _options]
    (if-let [bs (get @files path)]
      (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. bs)})
      fs/does-not-exist-response))
  (put-file [_ path input-stream _metadata]
    (let [baos (ByteArrayOutputStream.)]
      (io/copy input-stream baos)
      (swap! files assoc path (.toByteArray baos)))
    (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. (byte-array 0))})))

(defn- repeatable-store
  [contents]
  (->RepeatableMemFS (atom contents)))

;; A DistributedFileSystem test double whose get-file blocks every caller on
;; `release-latch` (after counting down `entered-latch`) for any path in
;; `blocked-paths` — everything else reads/writes normally. Used to occupy
;; every decode permit with genuinely in-flight (blocked) decodes on distinct
;; keys, then probe the busy fallback against other, unblocked keys.
(defrecord BusyLatchFS [files blocked-paths ^CountDownLatch entered-latch ^CountDownLatch release-latch]
  fs/DistributedFileSystem
  (ls [_ _path _options] nil)
  (get-file [_ path _options]
    (when (contains? blocked-paths path)
      (.countDown entered-latch)
      (.await release-latch))
    (if-let [bs (get @files path)]
      (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. bs)})
      fs/does-not-exist-response))
  (put-file [_ path input-stream _metadata]
    (let [baos (ByteArrayOutputStream.)]
      (io/copy input-stream baos)
      (swap! files assoc path (.toByteArray baos)))
    (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. (byte-array 0))})))

(defn- fixture-bytes
  []
  (java.nio.file.Files/readAllBytes
   (.toPath (io/file (io/resource "public/images/example-image.png")))))

(defn- rendition-exists?
  [store photo-id category ext]
  (not (fs/does-not-exist? (fs/get-file store (format "media/%s/%s.%s" photo-id category ext) {}))))

(defn- wait-until
  "Poll `pred-fn` every 20ms until truthy or `timeout-ms` elapses. Only ever
  used to wait on the resize gate's async worker — the assertion itself is
  behavioral (the rendition eventually appears), never a timing measurement."
  [pred-fn timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred-fn)                              true
        (> (System/currentTimeMillis) deadline) false
        :else                                   (do (Thread/sleep 20) (recur))))))

(defn- read-body-bytes
  ^bytes [body]
  (if (instance? InputStream body)
    (.readAllBytes ^InputStream body)
    body))

(defn- seed-photo-version!
  "Insert a photo + a single photo_version row for `category`, so
  `get-full-photos` can resolve a GET's `path` — independent of whether
  `category`'s bytes actually exist in the media store. That's the point:
  the DB and the store can disagree (an async warm still queued/running),
  and the heal path exists to reconcile it."
  [database hostname photo-id ext category]
  (albums-api/create-photo! database {:id photo-id :hostname hostname})
  (albums-api/create-photo-version-2!
   database
   [(albums-api/make-image-version nil photo-id hostname ext (u/now) category)]))

(defn- photo-request
  [database store gate hostname photo-id filename]
  {:components {:database                database
                :static-content-adapters {http-tenant/media-store store}
                :resize-gate             gate}
   :tenant     {:hostname hostname}
   :headers    {}
   :parameters {:path {:photo-id photo-id :filename filename}}})

(deftest serve-photo-heals-a-missing-rendition-on-an-idle-gate-test
  (testing "a rendition GET that 404s against the store, with an idle resize gate, is healed
            synchronously (200); a second GET is a genuine store hit with no re-decode"
    (let [database (embedded-h2/fresh-db!)
          hostname "andrewslai.com"
          photo-id (u/uuid)
          raw      (fixture-bytes)
          store    (repeatable-store {(format "media/%s/raw.png" photo-id) raw})
          gate     (resize/make-resize-gate store {:max-concurrent 1})
          calls    (atom 0)
          orig     resize/resize-to]
      (try
        (seed-photo-version! database hostname photo-id "png" "gallery")
        (with-redefs [resize/resize-to (fn [bs ext ^long w ^long h] (swap! calls inc) (orig bs ext w h))]
          (let [response (photo/serve-photo (photo-request database store gate hostname photo-id "gallery.png"))]
            (is (= 200 (:status response)))
            (is (pos? (alength (read-body-bytes (:body response))))))
          (is (rendition-exists? store photo-id "gallery" "png")
              "the healed rendition must now exist in the store")

          (testing "a second GET is served straight from the store — a real 200 hit"
            (let [response2 (photo/serve-photo (photo-request database store gate hostname photo-id "gallery.png"))]
              (is (= 200 (:status response2)))))

          (is (= 1 @calls) "resize-to must run exactly once across both GETs — no re-decode on the hit"))
        (finally (resize/stop! gate))))))

(deftest serve-photo-with-no-raw-falls-through-to-not-found-test
  (testing "rendition AND raw both missing: heal-or-enqueue! reports :no-raw, and the
            original 404 stands"
    (let [database (embedded-h2/fresh-db!)
          hostname "andrewslai.com"
          photo-id (u/uuid)
          store    (repeatable-store {})
          gate     (resize/make-resize-gate store {:max-concurrent 1})]
      (try
        (seed-photo-version! database hostname photo-id "png" "gallery")
        (let [response (photo/serve-photo (photo-request database store gate hostname photo-id "gallery.png"))]
          (is (= 404 (:status response))))
        (finally (resize/stop! gate))))))

(deftest serve-photo-busy-gate-serves-the-raw-no-store-and-enqueues-test
  (testing "every decode permit held: distinct-key rendition GETs each get 200 with the RAW
            body, Cache-Control no-store, no ETag — and the real rendition is enqueued for
            the worker to make once permits free (asserted behaviorally, never by latency)"
    (let [database      (embedded-h2/fresh-db!)
          hostname      "andrewslai.com"
          raw           (fixture-bytes)
          n             resize/MAX-CONCURRENT
          k             (+ n 2)
          occupier-ids  (repeatedly n u/uuid)
          victim-ids    (repeatedly k u/uuid)
          occupied-raws (into {} (map (fn [id] [(format "media/%s/raw.png" id) raw])) occupier-ids)
          victim-raws   (into {} (map (fn [id] [(format "media/%s/raw.png" id) raw])) victim-ids)
          entered-latch (CountDownLatch. n)
          release-latch (CountDownLatch. 1)
          store         (->BusyLatchFS (atom (merge occupied-raws victim-raws))
                                        (set (keys occupied-raws))
                                        entered-latch release-latch)
          gate          (resize/make-resize-gate store)]
      (try
        (doseq [id victim-ids]
          (seed-photo-version! database hostname id "png" "gallery"))

        ;; Occupy every permit with an in-flight (blocked) decode on distinct keys.
        (doseq [id occupier-ids]
          (is (true? (resize/enqueue-warm! gate id "png" "gallery"))))
        (is (.await entered-latch 5000 TimeUnit/MILLISECONDS)
            "all MAX-CONCURRENT workers must be mid-decode (permits held) before probing the busy path")
        (is (zero? (.availablePermits ^Semaphore (:permit gate)))
            "sanity: every permit is held by an occupier at this point")

        (doseq [id victim-ids]
          (let [response (photo/serve-photo (photo-request database store gate hostname id "gallery.png"))]
            (is (= 200 (:status response)) (str "photo " id))
            (is (= "no-store" (get-in response [:headers "Cache-Control"])) (str "photo " id))
            (is (not (contains? (:headers response) "ETag")) (str "photo " id))
            (is (= (seq raw) (seq (read-body-bytes (:body response)))) (str "photo " id))))

        (.countDown release-latch)

        (doseq [id victim-ids]
          (is (wait-until #(rendition-exists? store id "gallery" "png") 5000)
              (str "the busy path must have enqueued a warm task the worker eventually drains, for " id)))
        (finally (resize/stop! gate))))))
