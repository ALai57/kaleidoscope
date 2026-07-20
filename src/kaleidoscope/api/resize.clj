(ns kaleidoscope.api.resize
  "Pure image-resize core — in-process replacement for the disabled AWS
  Lambda resizer. No filesystem, network, or DB access: every function here
  is bytes-in, bytes/long-out, so it can be unit tested without any I/O
  fixtures beyond raw image bytes."
  (:require [clojure.string :as string])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
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
