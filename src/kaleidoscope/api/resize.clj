(ns kaleidoscope.api.resize
  "In-process replacement for the disabled AWS Lambda resizer.

  Two halves:
  1. A PURE core (`RENDITIONS`, `MAX-SOURCE-PIXELS`, `resize-to`,
     `source-pixels`) — bytes-in, bytes/long-out, no I/O.
  2. The resize gate — an in-memory queue + bounded worker pool + shared
     permit that drives resizing against a `DistributedFileSystem` media
     store (`make-resize-gate`, `resize-one!`, `enqueue-warm!`)."
  (:require [clojure.string :as string]
            [kaleidoscope.persistence.filesystem :as fs]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent LinkedBlockingQueue Semaphore TimeUnit]
           [javax.imageio ImageIO]
           [net.coobird.thumbnailator Thumbnails]))

(def RENDITIONS
  "Named output boxes [width height] that `resize-to` fits an image into,
  mirroring the renditions the old Lambda resizer produced."
  {"thumbnail" [100 100]
   "gallery"   [165 165]
   "monitor"   [1920 1080]
   "mobile"    [1200 630]})

(def MAX-SOURCE-PIXELS
  "Guard against decompression-bomb inputs: reject sources whose
  width*height (from the header alone) exceeds this before ever decoding
  pixel data."
  100000000)

(defn source-pixels
  "Read width*height from the image header without decoding pixel data.
  Throws if no ImageIO reader can be found for `raw-bytes`."
  ^long [^bytes raw-bytes]
  (with-open [iis (ImageIO/createImageInputStream (ByteArrayInputStream. raw-bytes))]
    (let [readers (iterator-seq (ImageIO/getImageReaders iis))
          reader  (or (first readers)
                      (throw (ex-info "No ImageIO reader found for input bytes"
                                      {:kaleidoscope/error :unreadable-image})))]
      (try
        (.setInput reader iis)
        (* (long (.getWidth reader 0)) (long (.getHeight reader 0)))
        (finally
          (.dispose reader))))))

(defn- output-format
  "Thumbnailator's outputFormat argument for a given file extension."
  [ext]
  (case (string/lower-case ext)
    "png"          "png"
    ("jpg" "jpeg") "jpg"
    ext))

(defn resize-to
  "Resize `raw-bytes` to fit within a `w`x`h` box, preserving aspect ratio
  (never upscaling beyond the source), and encode the result as `ext`.
  Returns the resized image as a byte array."
  ^bytes [^bytes raw-bytes ext ^long w ^long h]
  (with-open [in   (ByteArrayInputStream. raw-bytes)
              baos (ByteArrayOutputStream.)]
    (-> (Thumbnails/of (into-array java.io.InputStream [in]))
        (.size w h)
        (.keepAspectRatio true)
        (.outputFormat (output-format ext))
        (.toOutputStream baos))
    (.toByteArray baos)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The resize gate — in-memory queue + bounded worker pool + shared permit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def HEAP-BUDGET-BYTES
  "ESTIMATE, not measured — pending the profiling call-out in the plan's
  Task 2 Step 0 (measure peak decode heap for a 1920x1080 subsample on a
  representative large raw, then replace this value). The target box is
  ~1 GB / `-Xmx512m`; this conservatively reserves ~200 MB of that for
  simultaneous in-flight decode buffers, leaving headroom for Jetty, the
  JVM itself, and normal request handling."
  (* 200 1024 1024))

(def PEAK-RENDITION-BYTES
  "ESTIMATE, not measured — see HEAP-BUDGET-BYTES. Rough upper bound on the
  heap a single subsampled decode-and-resize can consume (subsampled source
  read + Thumbnailator's working buffers + the encoded output byte array).
  `source-pixels`/MAX-SOURCE-PIXELS already reject decompression-bomb
  sources before any decode, and ImageIO/Thumbnailator read subsampled
  (never at native resolution) — a resize to the largest rendition box
  (1920x1080, `monitor`) should peak in the tens of MB; 100 MB is a
  generous round-up pending measurement."
  (* 100 1024 1024))

(def MAX-CONCURRENT
  "Number of decodes allowed to run at once — derived data, not a knob:
  floor(heap budget / peak-per-decode), never less than 1. With the
  estimates above this currently yields 2."
  (max 1 (quot HEAP-BUDGET-BYTES PEAK-RENDITION-BYTES)))

(assert (>= HEAP-BUDGET-BYTES PEAK-RENDITION-BYTES)
        "HEAP-BUDGET-BYTES must afford at least one concurrent decode")

(def ACQUIRE-TIMEOUT-MS
  "How long the *serve* path (a later task's `heal-or-enqueue!`) is willing
  to wait for a free decode permit before giving up and falling back to
  enqueue + serve-raw. Distinct from RESIZE-TIMEOUT-MS — this bounds how
  long a web request thread blocks; that bounds how long a decode may run."
  250)

(def RESIZE-TIMEOUT-MS
  "Deadline for a single decode+encode+write. See `resize-one!` for the
  ImageIO-uninterruptible caveat this deadline can't fully solve."
  60000)

(def QUEUE-CAPACITY
  "Bound on the resize gate's backlog of warm tasks. `enqueue-warm!` never
  blocks on a full queue — it drops and logs (see `dropped-enqueue-count`)."
  512)

(defn- media-raw-path
  ^String [photo-id ext]
  (format "media/%s/raw.%s" photo-id ext))

(defn- media-rendition-path
  ^String [photo-id category ext]
  (format "media/%s/%s.%s" photo-id category ext))

(defn resize-one!
  "Produce (or reuse) one rendition of one photo in `gate`'s store.

  PRECONDITION: the caller has already acquired exactly one permit from
  `(:permit gate)` before calling this function. Ownership of *releasing*
  that permit transfers to this call:
  - On the fast paths (`:hit`, `:failed` for a missing raw, `:bad-source`)
    the permit is released before this function returns.
  - On the decode path, the actual resize+write runs inside a `future` so
    a RESIZE-TIMEOUT-MS deadline can be enforced via `deref`. ImageIO
    decodes are not interruptible, so a timed-out decode's underlying
    thread keeps running to completion regardless — the permit is
    released from that future's own `finally`, i.e. only when the decode
    thread *actually* ends, not when this function returns `:failed` on
    the deadline. Releasing early here would let a fresh task acquire the
    same slot while the old decode is still consuming its share of
    HEAP-BUDGET-BYTES, silently doubling concurrent memory use — exactly
    what the Semaphore exists to prevent.

  Returns one of:
    {:outcome :hit}                — the rendition object already existed
                                      (checked *after* the permit was
                                      acquired — a best-effort work-saver
                                      once some decode has completed, not a
                                      per-key lock: at MAX-CONCURRENT > 1,
                                      two callers can still race into a
                                      concurrent decode of the same cold
                                      key. That's bounded (<= MAX-CONCURRENT)
                                      and idempotent (identical overwrite),
                                      not a hazard.)
    {:outcome :made :bytes bytes}  — decoded, resized, and written.
    {:outcome :bad-source}         — raw exceeds MAX-SOURCE-PIXELS, or
                                      isn't a decodable image. Permanent
                                      and cheap to re-detect (header-only
                                      read), so deliberately not cached.
    {:outcome :failed}             — raw missing, a store error, a decode
                                      timeout, or any other transient
                                      problem. Not cached — a later view
                                      (serve-path heal) or `media:reconcile`
                                      re-attempts."
  [gate photo-id category ext]
  (let [{:keys [store ^Semaphore permit]} gate
        [w h]          (get RENDITIONS category)
        rendition-path (media-rendition-path photo-id category ext)
        raw-path       (media-raw-path photo-id ext)
        released?      (atom false)
        release!       (fn []
                          (when (compare-and-set! released? false true)
                            (.release permit)))
        decode-fut     (atom nil)]
    (span/with-span! {:name       "kaleidoscope.resize.resize-one!"
                       :attributes {"resize.photo-id" (str photo-id)
                                    "resize.category" (str category)}}
      (let [outcome
            (try
              (cond
                (not (fs/does-not-exist? (fs/get-file store rendition-path {})))
                (do (release!)
                    {:outcome :hit})

                :else
                (let [raw-obj (fs/get-file store raw-path {})]
                  (if (fs/does-not-exist? raw-obj)
                    (do (release!)
                        (log/errorf "resize failed: raw missing for photo %s (%s)" photo-id raw-path)
                        {:outcome :failed})
                    (let [raw-bytes (.readAllBytes ^java.io.InputStream (fs/object-content raw-obj))
                          pixels    (try (source-pixels raw-bytes)
                                         (catch InterruptedException e (throw e))
                                         (catch Throwable _ nil))]
                      (if (or (nil? pixels) (> (long pixels) (long MAX-SOURCE-PIXELS)))
                        (do (release!)
                            (log/errorf "resize bad-source: photo %s category %s (pixels=%s limit=%s)"
                                        photo-id category pixels MAX-SOURCE-PIXELS)
                            {:outcome :bad-source})
                        (let [fut (future
                                    (try
                                      (let [resized (resize-to raw-bytes ext w h)]
                                        (fs/put-file store rendition-path (ByteArrayInputStream. resized) {})
                                        {:outcome :made :bytes resized})
                                      (catch Throwable t
                                        (log/errorf t "resize failed during decode/write: photo %s category %s"
                                                    photo-id category)
                                        {:outcome :failed})
                                      (finally (release!))))
                              _      (reset! decode-fut fut)
                              result (deref fut RESIZE-TIMEOUT-MS ::timeout)]
                          (if (= result ::timeout)
                            (do
                              (log/errorf (str "resize timed out after %dms: photo %s category %s — the "
                                                "decode is not interruptible and may still be running; its "
                                                "permit stays held until it actually finishes, never "
                                                "released early")
                                          RESIZE-TIMEOUT-MS photo-id category)
                              {:outcome :failed})
                            (do
                              (when (= :made (:outcome result))
                                (log/infof "resize made: photo %s category %s (%d bytes)"
                                           photo-id category (alength ^bytes (:bytes result))))
                              result))))))))
              (catch InterruptedException _
                ;; Interruptible blocking points in this body: the `deref`
                ;; above, and (via `fs/get-file`'s own internal future/deref)
                ;; the raw/rendition reads before the decode future exists.
                ;; `stop!` interrupts worker threads, and where that interrupt
                ;; lands determines who owns the permit:
                ;;  - If it lands while blocked on `deref` (decode-fut already
                ;;    created), the in-flight future is still running (ImageIO
                ;;    decodes aren't interruptible) and its own `finally`
                ;;    releases the permit when the decode actually finishes —
                ;;    releasing here too would let a fresh task double up on
                ;;    the same memory slot, so we must NOT release.
                ;;  - If it lands earlier (e.g. inside `fs/get-file`, before
                ;;    the decode future was ever created), there is no future
                ;;    to release it later — skipping release! here would leak
                ;;    the permit forever. So: release iff no future took
                ;;    ownership.
                ;; Either way, restore the interrupt flag so `run-worker!`'s
                ;; loop (and its next `.acquire`) still observes it and exits
                ;; instead of silently surviving past `stop!`.
                (.interrupt (Thread/currentThread))
                (when (nil? @decode-fut)
                  (release!))
                (log/warnf "resize interrupted: photo %s category %s — permit %s"
                           photo-id category
                           (if (nil? @decode-fut)
                             "released (no decode was in flight)"
                             "stays held until the in-flight decode finishes"))
                {:outcome :failed})
              (catch Throwable t
                (release!)
                (log/errorf t "resize failed: unexpected error for photo %s category %s" photo-id category)
                {:outcome :failed}))]
        (span/add-span-data! {:attributes {"resize.outcome" (name (:outcome outcome))}})
        outcome))))

(defn- run-worker!
  "Body of one resize-gate worker thread: pull tasks off the queue forever,
  resizing every requested category under a held permit. Each task
  iteration is wrapped in `catch Throwable` — a single bad task (or a bug
  in `resize-one!`) must never kill the loop, since these are the only
  threads that will ever drain the queue. `InterruptedException` is the
  one thing allowed to end the loop, for `stop!`."
  [gate]
  (let [{:keys [^LinkedBlockingQueue queue ^Semaphore permit]} gate]
    (try
      (while (not (.isInterrupted (Thread/currentThread)))
        (try
          (let [task (.take queue)]
            (doseq [category (:categories task)]
              (.acquire permit)
              (resize-one! gate (:photo-id task) category (:ext task))))
          (catch InterruptedException e
            (throw e))
          (catch Throwable t
            (log/error t "resize worker: unexpected error processing a task; continuing"))))
      (catch InterruptedException _
        (log/info "resize worker: stopping (interrupted)")))))

(defn make-resize-gate
  "Build a resize gate over `store` (a `DistributedFileSystem`): a bounded
  `LinkedBlockingQueue` of warm tasks, a `Semaphore` capping concurrent
  decodes, and that many daemon worker threads draining the queue.

  `opts` — `{:max-concurrent n}` overrides MAX-CONCURRENT so tests can pin
  a known permit count instead of depending on the (estimate-derived)
  default. Not exposed for production tuning."
  ([store] (make-resize-gate store {}))
  ([store {:keys [max-concurrent] :or {max-concurrent MAX-CONCURRENT}}]
   (let [queue   (LinkedBlockingQueue. (int QUEUE-CAPACITY))
         permit  (Semaphore. (int max-concurrent))
         gate    {:store store :queue queue :permit permit :max-concurrent max-concurrent}
         workers (mapv (fn [i]
                          (doto (Thread. ^Runnable (fn [] (run-worker! gate))
                                         (format "resize-worker-%d" i))
                            (.setDaemon true)
                            (.start)))
                        (range max-concurrent))]
     (assoc gate :workers workers))))

(defn stop!
  "Interrupt and join every worker thread in `gate`. Tests must call this
  for every gate they start, or worker threads leak across test runs."
  [gate]
  (doseq [^Thread t (:workers gate)]
    (.interrupt t))
  (doseq [^Thread t (:workers gate)]
    (.join t 5000)))

(def dropped-enqueue-count
  "Total number of `enqueue-warm!` calls dropped because the gate's queue
  was full. Exists so a drop is observable (not just logged) — from a
  caller's perspective a drop is silent otherwise, and the plan requires
  it not be: a full queue is caught later by the serve-path 404 heal or
  `media:reconcile`, but must be visible in the meantime."
  (atom 0))

(defn enqueue-warm!
  "Non-blocking `offer` of a warm task for `photo-id` onto `gate`'s queue —
  one category per arg, or every RENDITIONS category if none are given.
  Used by upload, the busy serve path, and reconcile.

  Returns `true` if the task was enqueued, `false` if the queue was full
  (`offer` returned false) — in which case a WARN is logged and
  `dropped-enqueue-count` is bumped so the drop is never silent."
  [gate photo-id ext & categories]
  (let [cats (or (seq categories) (keys RENDITIONS))
        task {:photo-id photo-id :ext ext :categories cats}]
    (span/with-span! {:name       "kaleidoscope.resize.enqueue-warm!"
                       :attributes {"resize.photo-id"   (str photo-id)
                                    "resize.categories" (str cats)}}
      (if (.offer ^LinkedBlockingQueue (:queue gate) task)
        true
        (do
          (swap! dropped-enqueue-count inc)
          (log/warnf "resize queue full (capacity %d); dropping warm task for photo %s categories %s"
                     QUEUE-CAPACITY photo-id cats)
          (span/add-span-data! {:attributes {"resize.dropped" true}})
          false)))))

(defn heal-or-enqueue!
  "Serve-path fast-fail self-heal: called when a GET for a rendition 404s.
  Tries to make the rendition right now, but never blocks the web request
  thread beyond ACQUIRE-TIMEOUT-MS waiting for a decode permit — if none is
  immediately free, falls back to enqueueing an async warm for just this
  category and tells the caller to serve the raw meanwhile.

  PERMIT-OWNERSHIP CONTRACT: identical to `run-worker!`'s. On a successful
  `tryAcquire`, `resize-one!` is called exactly as the worker calls it —
  holding the one acquired permit, with `resize-one!` (and, on the decode
  path, its future's `finally`) owning the release. This function must NOT
  itself release that permit under any outcome; doing so would race a fresh
  task into the same memory slot while a decode may still be running (see
  `resize-one!`'s docstring for why that early-release bug is exactly what
  the Semaphore exists to prevent).

  Returns one of:
    {:made bytes}  — the rendition now exists in the store; bytes are ready
                     to serve without a re-read (fetched from the store on a
                     `:hit` outcome, since a hit alone carries no bytes).
    :busy          — no permit was free within ACQUIRE-TIMEOUT-MS, or the
                     resize attempt failed transiently (`:failed`). Either
                     way a warm task for this single category was enqueued
                     (best-effort — `enqueue-warm!` never blocks and drops
                     silently-but-counted on a full queue) so a later view
                     or `media:reconcile` retries it. The caller should serve
                     the raw right now.
    :no-raw        — the raw object itself is missing; nothing to heal from.
    :bad-source    — the raw exceeds MAX-SOURCE-PIXELS or isn't decodable by
                     ImageIO; permanent and cheap to re-detect, so not worth
                     enqueueing."
  [gate photo-id category ext]
  (let [{:keys [store ^Semaphore permit]} gate
        raw-path (media-raw-path photo-id ext)]
    (span/with-span! {:name       "kaleidoscope.resize.heal-or-enqueue!"
                       :attributes {"resize.photo-id" (str photo-id)
                                    "resize.category" (str category)}}
      (if (fs/does-not-exist? (fs/get-file store raw-path {}))
        :no-raw
        (if (.tryAcquire permit ACQUIRE-TIMEOUT-MS TimeUnit/MILLISECONDS)
          ;; Permit acquired: hand it to resize-one! exactly as run-worker!
          ;; does. resize-one! owns releasing it from here on.
          (let [{:keys [outcome bytes]} (resize-one! gate photo-id category ext)]
            (case outcome
              :made       {:made bytes}
              :hit        (let [rendition-path (media-rendition-path photo-id category ext)
                                 obj            (fs/get-file store rendition-path {})]
                            {:made (.readAllBytes ^java.io.InputStream (fs/object-content obj))})
              :bad-source :bad-source
              :failed     (do (enqueue-warm! gate photo-id ext category)
                               :busy)))
          (do (enqueue-warm! gate photo-id ext category)
              :busy))))))
