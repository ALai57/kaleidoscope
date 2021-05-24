(ns andrewslai.clj.persistence.s3
  (:require [amazonica.core :as amazon]
            [amazonica.aws.s3 :as s3]
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
           [com.amazonaws.auth.profile ProfileCredentialsProvider]))

(def CustomAWSCredentialsProviderChain
  (AWSCredentialsProviderChain.
   [^AWSCredentialsProvider (new EnvironmentVariableCredentialsProvider)
    ^AWSCredentialsProvider (new ProfileCredentialsProvider)
    ^AWSCredentialsProvider (new ContainerCredentialsProvider)]))

#_(bean (.getCredentials CustomAWSCredentialsProviderChain))

(defprotocol FileSystem
  (ls [_ path])
  (get-file [_ path])
  (put-file [_ path]))

(defn exception-response
  [{:keys [status-code] :as exception-map}]
  (case status-code
    404 (not-found)
    (internal-server-error "Unknown exception")))

(defn make-s3
  [config]
  (reify FileSystem
    (ls [_ path]
      (log/info "List objects in S3: " path)
      (->> (s3/list-objects-v2 (:credentials config)
                               {:bucket-name (:bucket-name config)
                                :prefix      path})
           :object-summaries
           (drop 1)
           (map (fn [m] (select-keys m [:key :size :etag])))))
    (get-file [_ path]
      (log/info "Get object in S3: " path)
      (try
        (-> (s3/get-object (:bucket-name config) path)
            :input-stream)
        (catch Exception e
          (exception-response (amazon/ex->map e)))))))

(comment
  (import '[com.amazonaws.auth DefaultAWSCredentialsProviderChain])
  (s3/list-buckets)

  (amazon/get-credentials nil)

  (keys (bean (.getCredentials (DefaultAWSCredentialsProviderChain/getInstance))))

  (ls (make-s3 {:bucket-name "andrewslai-wedding"
                :credentials (DefaultAWSCredentialsProviderChain/getInstance)})
      "media/")

  (get-file (make-s3 {:bucket-name "andrewslai-wedding"})
            "index.html")

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
  (s3/get-object "andrewslai-wedding" "index.html")
  )
