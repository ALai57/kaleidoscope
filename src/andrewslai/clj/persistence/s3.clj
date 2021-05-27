(ns andrewslai.clj.persistence.s3
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.string :as string]
            [ring.util.http-response :refer [content-type not-found ok
                                             internal-server-error
                                             resource-response]]
            [taoensso.timbre :as log])
  (:import [com.amazonaws.auth
            AWSCredentialsProviderChain
            EnvironmentVariableCredentialsProvider
            ContainerCredentialsProvider
            EC2ContainerCredentialsProviderWrapper]
           [com.amazonaws.auth.profile ProfileCredentialsProvider]
           [java.net URL URLStreamHandler URLConnection URLStreamHandlerFactory URLClassLoader]))

(def CustomAWSCredentialsProviderChain
  (AWSCredentialsProviderChain.
   [^AWSCredentialsProvider (new EnvironmentVariableCredentialsProvider)
    ^AWSCredentialsProvider (new ProfileCredentialsProvider)
    ^AWSCredentialsProvider (new ContainerCredentialsProvider)]))

#_(bean (.getCredentials CustomAWSCredentialsProviderChain))

(defn exception-response
  [{:keys [status-code] :as exception-map}]
  (case status-code
    404 (not-found)
    (internal-server-error "Unknown exception")))

(defn make-s3
  [config]
  (reify fs/FileSystem
    (ls [_ path]
      (log/info "List objects in S3: " path)
      (->> (s3/list-objects-v2 (:credentials config)
                               {:bucket-name (:bucket-name config)
                                :prefix      path})
           :object-summaries
           (drop 1)
           (map (fn [m] (select-keys m [:key :size :etag])))
           seq))
    (get-file [_ path]
      (log/info "Get object in S3: " path)
      (try
        (-> (s3/get-object (:bucket-name config) path)
            :input-stream)
        (catch Exception e
          (exception-response (amazon/ex->map e)))))))

(defrecord S3 [config]
  fs/FileSystem
  (ls [_ path]
    (log/info "List objects in S3: " path)
    (->> (s3/list-objects-v2 (:credentials config)
                             {:bucket-name (:bucket-name config)
                              :prefix      path})
         :object-summaries
         (drop 1)
         (map (fn [m] (select-keys m [:key :size :etag])))
         seq))
  (get-file [_ path]
    (log/info "Get object in S3: " path)
    (try
      (-> (s3/get-object (:credentials config) {:bucket-name (:bucket-name config)
                                                :key path})
          :input-stream)
      (catch Exception e
        (println e)
        (exception-response (amazon/ex->map e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration for s3-connections
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def s3-connections
  (reduce (fn [m bucket]
            (conj m {bucket (->S3 {:bucket-name bucket
                                   :credentials CustomAWSCredentialsProviderChain})}))
          {}
          ["andrewslai-wedding"]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creating a classloader that can load resources from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn s3-loader
  [bucket-name]
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [s]
      (URL. (format "s3p:/%s/%s" bucket-name s)))))

(defmethod ring.util.response/resource-data :s3p
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))

(comment
  (string/split "s3p:/andrewslai-wedding/andrewlai" #"/")

  (.getResource (s3-loader "andrewslai-wedding")
                "media/")


  (ring.util.response/resource-response "media/"
                                        {:loader (s3-loader "andrewslai-wedding")})

  (ring.util.response/resource-response "media/rings.jpg"
                                        {:loader (s3-loader "andrewslai-wedding")})


  (.getURL (.openConnection (URL. "s3p:/andrewslai-wedding/media/")))

  (str (URL. "s3p:/media/"))

  (get-file (get s3-connections "andrewslai-wedding")
            "wedding-index.html")
  )

(comment

  (import '[com.amazonaws.auth DefaultAWSCredentialsProviderChain])
  (s3/list-buckets)

  (amazon/get-credentials nil)

  (keys (bean (.getCredentials CustomAWSCredentialsProviderChain)))

  (ls (make-s3 {:bucket-name "andrewslai-wedding"
                :credentials CustomAWSCredentialsProviderChain})
      "media/")

  (get-file (make-s3 {:bucket-name "andrewslai-wedding"})
            "wedding-index.html")

  (get-file (make-s3 {:bucket-name "andrewslai-wedding"})
            "media")

  (spit "myindex.html" "<h1>HELLO</h1>")

  (s3/put-object :bucket-name "andrewslai-wedding"
                 :key "index.html"
                 :file (clojure.java.io/file "myindex.html"))

  (let [img    "clojure-logo.png"
        home   "/home/andrew/dev/andrewslai/resources/public/images/"]
    (s3/put-object :bucket-name "andrewslai-wedding"
                   :key         (str "media/" img)
                   :file        (clojure.java.io/file (str home img))))

  (slurp (clojure.java.io/resource "public/images/clojure-logo.png"))
  (slurp (clojure.java.io/file "/home/andrew/dev/andrewslai/resources/public/images/clojure-logo.png"))
  (s3/get-object "andrewslai-wedding" "wedding-index.html")
  (s3/get-object CustomAWSCredentialsProviderChain {:bucket-name "andrewslai-wedding"
                                                    :key "wedding-index.html"})
  )
