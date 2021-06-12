(ns andrewslai.clj.wedding-routes-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.memory :as memory]
            [andrewslai.clj.protocols.core :as protocols]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.routes.wedding :as wedding]
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

;; The org.apache.commons.fileupload implementation checks if there is a "filename"
;; header inside the content disposition to figure out if something is a file or not
;;  that determines whether the part is added as a file, or as a string

;; EXAMPLE GOOD ENCODING HEADERS
;;--9EYWD7pwVXEMJgxYsfUaKm_PNY6Frhl84Gp-ur2_
;;Content-Disposition: form-data; name="lock.svg"; filename="lock.svg"
;;Content-Type: application/octet-stream
;;Content-Transfer-Encoding: binary
;;
;;<?xml version="1.0" encoding="UTF-8" standalone="no"?>

(defmethod mp/add-part InputStream [^MultipartEntity m k ^InputStream f]
  (.addPart m
            (mp/ensure-string k)
            (InputStreamBody. f
                              (ContentType/create
                               (mime-type/ext-mime-type (mp/ensure-string k)))
                              (mp/ensure-string k))))


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
      (let [in-mem-fs (atom example-fs)
            app       (h/wedding-app {:auth         auth-backend
                                      :access-rules wedding/access-rules
                                      :storage      (memory/map->MemFS {:store in-mem-fs})})]
        (is (match? expected
                    (tu/app-request app
                                    {:request-method :get
                                     :uri            "/media/"
                                     :headers        (tu/auth-header ["wedding"])})))))
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


(deftest multipart-upload-test
  (let [request   {"title"        "Something good"
                   "Content-Type" "image/svg+html"
                   "name"         "lock.svg"
                   "lock.svg"     (-> "public/images/lock.svg"
                                      clojure.java.io/resource
                                      clojure.java.io/input-stream)}
        in-mem-fs (atom {})
        storage   (memory/map->MemFS {:store in-mem-fs})
        app       (h/wedding-app {:auth    (tu/authorized-backend)
                                  :access-rules wedding/access-rules
                                  :storage storage})]
    (is (match? {:status 200}
                (app (merge {:headers        (tu/auth-header ["wedding"])
                             :request-method :put
                             :uri            "/media/something"}
                            (mp/build request)))))
    (is (match? {"media" {"something" {:name     "something"
                                       :path     "media/something"
                                       :content  tu/file-input-stream?
                                       :metadata map?}}}
                @in-mem-fs))))

(comment
  (-> (mp/build {"title"        "Something good"
                 "Content-Type" "image/svg"
                 "name"         "lock.svg"
                 "lock.svg"     (-> "public/images/lock.svg"
                                    clojure.java.io/resource
                                    clojure.java.io/input-stream)})
      :body
      slurp)

  ;; Working PUT request to the app
  (http/put "http://caheriaguilar.and.andrewslai.com.localhost:5000/media/lock.svg"
            {:multipart
             [{:name "name" :content "lock.svg"}
              {:name "content-type" :content "image/svg+xml"}
              {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
              {:name "lock.svg" :content (-> "public/images/lock.svg"
                                             clojure.java.io/resource
                                             clojure.java.io/input-stream)}]})

  )
