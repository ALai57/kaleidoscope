(ns andrewslai.clj.utils.files.images
  (:require [andrewslai.clj.utils.files.core :as fc]
            [clojure.java.io :as io]
            [ring.util.mime-type :as mt])
  (:import [javax.imageio ImageIO]))

(defn- file-meta
  [file]
  (-> (io/input-stream file)
      (ImageIO/createImageInputStream)
      (ImageIO/read)
      bean))

(defmethod fc/extract-meta 'file/image
  [path file]
  (-> (file-meta file)
      (select-keys [:width :height :transparency :type])
      (assoc :content-type    (mt/ext-mime-type (str file))
             :content-length  (.length file))))

(comment
  ;; https://stackoverflow.com/questions/17189129/extract-images-width-height-color-and-type-from-byte-array/26122845

  (require '[andrewslai.clj.utils.files.core :as fc])
  (fc/extract-meta "resources/public/images/earthrise.png"
                   (io/file "resources/public/images/earthrise.png"))

  (fc/extract-meta "resources/public/images/lock.svg"
                   (io/file "resources/public/images/lock.svg"))

  (file-meta "resources/public/images/lock.svg")

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

  (-> (clojure.java.io/file "resources/public/images/lock.svg")
      clojure.java.io/input-stream
      (javax.imageio.ImageIO/createImageInputStream)
      (javax.imageio.ImageIO/read)
      #_bean
      )

  )
