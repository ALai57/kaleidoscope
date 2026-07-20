(ns kaleidoscope.api.resize-test
  "Pure core (`resize-to`/`source-pixels`) plus the resize gate:
  `make-resize-gate`, `resize-one!`, `enqueue-warm!`. The gate tests use an
  in-memory `DistributedFileSystem` (or a bespoke latching double for the
  concurrency test) so no real disk/network I/O is involved."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.resize :as resize]
            [kaleidoscope.persistence.filesystem :as fs])
  (:import [java.io ByteArrayInputStream]
           [java.util.concurrent CountDownLatch Semaphore TimeUnit]
           [javax.imageio ImageIO]))

(defn- fixture-bytes
  []
  (java.nio.file.Files/readAllBytes
   (.toPath (io/file (io/resource "public/images/example-image.png")))))

(defn- decode-dimensions
  "Read [width height] by fully decoding the given bytes (test-only helper —
  the production code path never decodes pixels just to measure a header)."
  [^bytes bs]
  (let [img (ImageIO/read (ByteArrayInputStream. bs))]
    [(.getWidth img) (.getHeight img)]))

(deftest resize-to-fits-every-rendition-box
  (let [raw (fixture-bytes)]
    (testing "each configured rendition fits within its box, aspect preserved"
      (doseq [[rendition-name [w h]] resize/RENDITIONS]
        (let [out             (resize/resize-to raw "png" w h)
              [out-w out-h]   (decode-dimensions out)]
          (testing rendition-name
            (is (pos? (alength out)))
            (is (<= out-w w))
            (is (<= out-h h))))))))

(deftest source-pixels-reads-the-fixtures-pixel-count
  (let [raw          (fixture-bytes)
        [w h]        (decode-dimensions raw)
        expected     (* (long w) (long h))]
    (is (= expected (resize/source-pixels raw)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The resize gate — make-resize-gate / resize-one! / enqueue-warm!
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord RepeatableMemFS [files]
  ;; A DistributedFileSystem test double storing raw bytes (not a
  ;; single-use InputStream) and handing back a *fresh* ByteArrayInputStream
  ;; on every get-file. `in-memory-impl/MemFS` instead returns the literal
  ;; stream object it was given at seed/put time — fine for a path read at
  ;; most once, but `resize-one!` reads `raw.<ext>` once per rendition
  ;; category, so `enqueue-warm!`'s worker draining 4 categories from one
  ;; task would see an already-exhausted stream on categories 2-4 against
  ;; MemFS. This double instead stores byte[] and materializes a new stream
  ;; per read, so it's safe to read the same path any number of times.
  fs/DistributedFileSystem
  (ls [_ _path _options] nil)
  (get-file [_ path _options]
    (if-let [bs (get @files path)]
      (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. bs)})
      fs/does-not-exist-response))
  (put-file [_ path input-stream _metadata]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (io/copy input-stream baos)
      (swap! files assoc path (.toByteArray baos)))
    (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. (byte-array 0))})))

(defn- mem-store
  "Compose a RepeatableMemFS from {path -> bytes}."
  [contents]
  (->RepeatableMemFS (atom contents)))

(defn- rendition-exists?
  [store photo-id category ext]
  (not (fs/does-not-exist? (fs/get-file store (format "media/%s/%s.%s" photo-id category ext) {}))))

(defn- wait-until
  "Poll `pred-fn` (no args) every 20ms until it's truthy or `timeout-ms`
  elapses. Used only for enqueue-warm!'s worker-drains-the-queue test,
  where *some* bounded wait for an async background thread is inherent —
  the assertion itself is behavioral (all 4 renditions eventually appear),
  never a wall-clock timing measurement."
  [pred-fn timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred-fn)                             true
        (> (System/currentTimeMillis) deadline) false
        :else                                  (do (Thread/sleep 20) (recur))))))

(deftest resize-one-sequential-made-then-hit
  (testing "first call decodes and writes; second call is a cached hit (resize-to spied to run once)"
    (let [raw   (fixture-bytes)
          store (mem-store {"media/p1/raw.png" raw})
          gate  (resize/make-resize-gate store {:max-concurrent 1})
          calls (atom 0)
          orig  resize/resize-to]
      (try
        ;; resize-to has ^long-hinted params, which makes the compiler emit
        ;; a primitive-arity invoke (IFn$OOLLO) at its call site in
        ;; resize-one! — the redef must carry matching ^long hints or the
        ;; primitive invoke throws a ClassCastException against a plain fn.
        (with-redefs [resize/resize-to (fn [bs ext ^long w ^long h] (swap! calls inc) (orig bs ext w h))]
          (.acquire ^Semaphore (:permit gate))
          (let [r1 (resize/resize-one! gate "p1" "gallery" "png")]
            (is (= :made (:outcome r1)))
            (is (pos? (alength ^bytes (:bytes r1))))
            (let [[out-w out-h] (decode-dimensions (:bytes r1))]
              (is (<= out-w 165))
              (is (<= out-h 165))))
          (is (rendition-exists? store "p1" "gallery" "png"))

          (.acquire ^Semaphore (:permit gate))
          (let [r2 (resize/resize-one! gate "p1" "gallery" "png")]
            (is (= :hit (:outcome r2))))

          (is (= 1 @calls) "resize-to must run exactly once across the made-then-hit pair"))
        (finally (resize/stop! gate))))))

(deftest resize-one-bad-source-over-pixel-guard-skips-decode
  (testing "a header reporting more than MAX-SOURCE-PIXELS is rejected before any decode"
    (let [raw   (fixture-bytes)
          store (mem-store {"media/p2/raw.png" raw})
          gate  (resize/make-resize-gate store {:max-concurrent 1})
          calls (atom 0)]
      (try
        (with-redefs [resize/source-pixels (fn [_] (inc resize/MAX-SOURCE-PIXELS))
                      resize/resize-to     (fn [& _] (swap! calls inc) (byte-array 0))]
          (.acquire ^Semaphore (:permit gate))
          (is (= :bad-source (:outcome (resize/resize-one! gate "p2" "gallery" "png"))))
          (is (zero? @calls) "resize-to must never run for a bad-source raw"))
        (finally (resize/stop! gate))))))

(deftest resize-one-undecodable-raw-is-bad-source
  (testing "bytes with no ImageIO reader are bad-source too, not an uncaught exception"
    (let [store (mem-store {"media/p2b/raw.png" (byte-array [1 2 3 4 5])})
          gate  (resize/make-resize-gate store {:max-concurrent 1})]
      (try
        (.acquire ^Semaphore (:permit gate))
        (is (= :bad-source (:outcome (resize/resize-one! gate "p2b" "gallery" "png"))))
        (finally (resize/stop! gate))))))

(deftest resize-one-missing-raw-fails-and-is-not-cached
  (testing "a missing raw is :failed every time — nothing is cached to make a later view forever fail"
    (let [store (mem-store {})
          gate  (resize/make-resize-gate store {:max-concurrent 1})]
      (try
        (.acquire ^Semaphore (:permit gate))
        (is (= :failed (:outcome (resize/resize-one! gate "nope" "gallery" "png"))))
        (.acquire ^Semaphore (:permit gate))
        (is (= :failed (:outcome (resize/resize-one! gate "nope" "gallery" "png"))))
        (finally (resize/stop! gate))))))

(defrecord LatchingRawFS
  ;; A DistributedFileSystem test double whose get-file, for exactly one
  ;; raw path, blocks every caller on `release-latch` after counting down
  ;; `entered-latch` — letting a test hold N concurrent resize-one! callers
  ;; mid-flight (all past the existence check, all about to decode) until
  ;; it deliberately releases them together. Every read returns a *fresh*
  ;; ByteArrayInputStream (not a shared, single-consumable one), so N
  ;; concurrent readers of the same raw path each see the whole image
  ;; rather than racing over one stream's read position.
  [raw-path raw-bytes renditions ^CountDownLatch entered-latch ^CountDownLatch release-latch]
  fs/DistributedFileSystem
  (ls [_ _path _options] nil)
  (get-file [_ path _options]
    (if (= path raw-path)
      (do (.countDown entered-latch)
          (.await release-latch)
          (fs/object {:version "raw" :content (ByteArrayInputStream. raw-bytes)}))
      (if-let [bs (get @renditions path)]
        (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. bs)})
        fs/does-not-exist-response)))
  (put-file [_ path input-stream _metadata]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (io/copy input-stream baos)
      (swap! renditions assoc path (.toByteArray baos)))
    (fs/object {:version (fs/md5 path) :content (ByteArrayInputStream. (byte-array 0))})))

(deftest resize-one-stampede-bounded-by-max-concurrent
  (testing "K > MAX-CONCURRENT racers on the same cold key: decodes are bounded (<= N), all K observe
            the object, and the write is idempotent — the double-check is a work-saver, not a lock"
    (let [n             resize/MAX-CONCURRENT
          k             (+ n 3)
          raw           (fixture-bytes)
          entered-latch (CountDownLatch. n)
          release-latch (CountDownLatch. 1)
          store         (->LatchingRawFS "media/stampede/raw.png" raw (atom {}) entered-latch release-latch)
          gate          (resize/make-resize-gate store) ;; the real MAX-CONCURRENT, per the plan
          calls         (atom 0)
          orig          resize/resize-to]
      (try
        (with-redefs [resize/resize-to (fn [bs ext ^long w ^long h] (swap! calls inc) (orig bs ext w h))]
          (let [futs (mapv (fn [_]
                              (future
                                (.acquire ^Semaphore (:permit gate))
                                (resize/resize-one! gate "stampede" "gallery" "png")))
                            (range k))]
            ;; Deterministic sync point: wait until exactly N callers have
            ;; acquired a permit and raced into the raw read (the Semaphore
            ;; guarantees no more than N ever can); only then release them
            ;; together to force a genuine concurrent decode.
            (is (.await entered-latch 5000 TimeUnit/MILLISECONDS)
                "N racers must reach the raw read within the deadline")
            (.countDown release-latch)
            (let [results (mapv deref futs)]
              (is (every? #(contains? #{:made :hit} (:outcome %)) results)
                  "every racer observes either :made or :hit, never :failed/:bad-source")
              (is (<= @calls n) "resize-to must run no more than MAX-CONCURRENT times")
              (is (pos? @calls) "the assertion above must not be vacuously true")
              (is (rendition-exists? store "stampede" "gallery" "png")))))
        (finally (resize/stop! gate))))))

(deftest enqueue-warm-worker-eventually-creates-every-rendition
  (testing "enqueue-warm! with no explicit categories drains to all four RENDITIONS via the worker"
    (let [raw   (fixture-bytes)
          store (mem-store {"media/p3/raw.png" raw})
          gate  (resize/make-resize-gate store {:max-concurrent 1})]
      (try
        (is (true? (resize/enqueue-warm! gate "p3" "png")))
        (is (wait-until #(every? (fn [category] (rendition-exists? store "p3" category "png"))
                                  (keys resize/RENDITIONS))
                         5000)
            "the worker must eventually produce all four renditions")
        (finally (resize/stop! gate))))))

(deftest enqueue-warm-drop-on-full-queue-is-observable
  (testing "offer false (queue full) is never silent — it's counted and the caller is told"
    (let [store (mem-store {})
          gate  (resize/make-resize-gate store {:max-concurrent 1})]
      (try
        ;; Stop the workers first so nothing drains the queue while we fill it.
        (resize/stop! gate)
        (dotimes [_ resize/QUEUE-CAPACITY]
          (is (true? (.offer ^java.util.concurrent.LinkedBlockingQueue (:queue gate)
                              {:photo-id "filler" :ext "png" :categories ["gallery"]}))))
        (let [before @resize/dropped-enqueue-count
              result (resize/enqueue-warm! gate "overflow" "png")]
          (is (false? result) "enqueue-warm! must report the drop, not swallow it")
          (is (> @resize/dropped-enqueue-count before)
              "the drop must be observable via the counter, not merely logged"))
        (finally nil)))))
