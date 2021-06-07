(ns andrewslai.clj.wedding-routes-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.memory :as memory]
            [andrewslai.clj.protocols.core :as protocols]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [clj-http.client :as http]
            [clojure.string :as string]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [peridot.multipart :as mp]
            [taoensso.timbre :as log])
  (:require [clojure.java.io :as io]
            [ring.util.codec :as codec]
            [ring.util.mime-type :as mime-type])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.nio.charset Charset)
           (org.apache.http.entity ContentType)
           (org.apache.http.entity.mime MultipartEntity)
           (org.apache.http.entity.mime.content InputStreamBody))

  )

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(defmethod mp/add-part InputStream [^MultipartEntity m k ^InputStream f]
  (.addPart m
            (mp/ensure-string k)
            (InputStreamBody. f (ContentType/create
                                 (mime-type/ext-mime-type (mp/ensure-string k))))))

(defn wedding-route
  [components options]
  (tu/http-request :get "/media/" components (assoc options
                                                    :app h/wedding-app)))


;; TODO: This is relying on the access control list and `wedding/access-rules`
;;        to determine authorization. Should test authorization instead of
;;        relying on that configuration
;;

(def example-fs
  "An in-memory filesystem used for testing"
  {"media" {"afile" (memory/file {:name     "afile"
                                  :content  {:qux :quz}
                                  :metadata {}})
            "adir"  {"anotherfile" (memory/file {:name     "afile"
                                                 :content  {:qux :quz}
                                                 :metadata {}})}}})

(deftest authorized-user-test
  (are [description auth-backend expected]
    (testing description
      (let [in-mem-fs (atom example-fs)]
        (is (match? expected
                    (tu/app-request
                     h/wedding-app
                     {:request-method :get
                      :uri            "/media/"
                      :headers        (tu/auth-header ["wedding"])}
                     {:auth           auth-backend
                      :wedding-storage (sc/static-content (memory/map->MemFS {:store in-mem-fs}))})))))
    "Authorized request returns 200"
    (tu/authorized-backend)
    {:status  200
     :headers {"Cache-Control" sc/no-cache}
     :body    [{:name "afile"} {:name "adir" :type "directory"}]}

    "Unauthorized request returns 401"
    (tu/unauthorized-backend)
    {:status 401}))

(defn tmpfile
  ([]
   (tmpfile "andrewslai-test" "test.txt"))
  ([fname]
   (tmpfile "andrewslai-test" fname))
  ([dir fname]
   (let [tmpdir    (tu/mktmpdir dir)
         tmpfile   (tu/mktmp fname tmpdir)]
     (str (.getAbsolutePath tmpdir) "/" (.getName tmpfile)))))


;; Create an object that reifies the filesystem protocol to use with the loader
(deftest multipart-upload-test
  (let [tmpfile     (tmpfile "multipart.txt")
        request     {"title"        "Something good"
                     "Content-Type" "image/svg"
                     "name"         "lock.svg"
                     "lock.svg"     (-> "public/images/lock.svg"
                                        clojure.java.io/resource
                                        clojure.java.io/input-stream)}
        in-mem-fs   (atom {})
        storage     (memory/map->MemFS {:store in-mem-fs})
        wedding-app (h/wedding-app {:auth    (tu/authorized-backend)
                                    :storage storage
                                    :static  (sc/static-content storage)})]
    (is (match? {:status 200}
                (wedding-app
                 (merge {:headers        (tu/auth-header ["wedding"])
                         :request-method :put
                         :uri            "/media/something"}
                        (mp/build request)))))
    (is (match? {"media" {"something" {:name "something"
                                       :path "/media/something"
                                       :content tu/buffered-input-stream?
                                       :metadata map?}}}
                @in-mem-fs))))

(comment
  (-> (mp/build {"title"        "Something good"
                 "Content-Type" "image/jpeg"
                 "eggplan"      "Eggplants"
                 "file.txt"     (-> "public/images/lock.svg"
                                    clojure.java.io/resource
                                    clojure.java.io/input-stream)})
      :body
      slurp)
  )

(comment
  (http/put "http://caheriaguilar.and.andrewslai.com.localhost:5000/media/something"
            {:multipart [{:name "title" :content "Something good"}
                         {:name "Content/type" :content "image/jpeg"}
                         {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                         {:name "lock.svg" :content (-> "public/images/lock.svg"
                                                        clojure.java.io/resource
                                                        clojure.java.io/input-stream)}]}
            )
  )


(comment
  (wedding-route {:auth (tu/authorized-backend)}
                 {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}})

  (auth/jwt-body (make-jwt {:hdr ""
                            :body (auth/clj->b64 {:realm_access {:roles ["wedding"]}})
                            :sig ""}))
  )
