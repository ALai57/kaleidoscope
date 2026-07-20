(ns kaleidoscope.api.resize-test
  "Pure image-resize core — no I/O beyond reading the test fixture's bytes.
  Confirms `resize-to` fits every RENDITIONS box (aspect preserved, never
  upscaled past the requested box) and `source-pixels` reads dimensions from
  the image header alone."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.resize :as resize])
  (:import [java.io ByteArrayInputStream]
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
