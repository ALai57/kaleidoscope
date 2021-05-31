(ns andrewslai.clj.files.images
  (:require [andrewslai.clj.files.core :as fc]
            [clojure.java.io :as io]
            [ring.util.mime-type :as mt])
  (:import [javax.imageio ImageIO]))

(defn- file-meta
  [file]
  (-> (io/input-stream file)
      (ImageIO/createImageInputStream)
      (ImageIO/read)
      bean))

(defmethod fc/extract-meta java.io.File
  [file]
  (let [m (file-meta file)]
    {:content-type   (mt/ext-mime-type (str file))
     :content-length (.length file)
     :image-metadata  (select-keys m [:width :height :transparency :type])}))

(comment
  ;; https://stackoverflow.com/questions/17189129/extract-images-width-height-color-and-type-from-byte-array/26122845
  (fc/extract-meta (io/file "resources/public/images/earthrise.png"))

  (-> (clojure.java.io/resource "public/images/earthrise.png")
      clojure.java.io/input-stream
      slurp
      (.getBytes)
      (java.io.ByteArrayInputStream.)
      (javax.imageio.ImageIO/createImageInputStream)
      (javax.imageio.ImageIO/read)
      )


  (bean (-> (clojure.java.io/resource "public/images/earthrise.png")
            clojure.java.io/input-stream
            (javax.imageio.ImageIO/createImageInputStream)
            (javax.imageio.ImageIO/read)))

  (-> (clojure.java.io/file "resources/public/images/earthrise.png")
      clojure.java.io/input-stream
      (javax.imageio.ImageIO/createImageInputStream)
      (javax.imageio.ImageIO/read)
      bean
      :data
      bean)

  )
