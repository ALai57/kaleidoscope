(ns andrewslai.clj.wedding-routes-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.memory :as memory]
            [andrewslai.clj.protocols.core :as protocols]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [andrewslai.clj.utils :as u]
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
           (org.apache.http.entity.mime.content InputStreamBody)))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

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
            app       (wedding/wedding-app {:auth         auth-backend
                                            :access-rules wedding/access-rules
                                            :storage      (memory/map->MemFS {:store in-mem-fs})})]
        (is (match? expected
                    (tu/app-request app
                                    {:request-method :get
                                     :uri            "/media/"
                                     :headers        (tu/auth-header ["wedding"])})))))
    "Authenticated request returns 200"
    (tu/authenticated-backend)
    {:status  200
     :headers {"Cache-Control" sc/no-cache}
     :body    [{:name "afile"} {:name "adir" :type "directory"}]}

    "Unauthenticated request returns 401"
    (tu/unauthenticated-backend)
    {:status 401}))

(defn ->multipart
  [{:keys [separator part-name file-name content-type content]}]
  (format "%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type:%s\r\nContent-Transfer-Encoding: binary\r\n\r\n%s\r\n"
          separator
          part-name
          file-name
          content-type
          content))

(defn assemble-multipart
  [separator parts]
  (let [mp-parts (map (comp ->multipart #(assoc % :separator (str "--" separator)))
                      parts)]

    {:headers {"content-type" (format "multipart/form-data; boundary=%s;" separator)}
     :body    (-> (format "%s--%s--" (apply str mp-parts) separator)
                  (.getBytes)
                  (clojure.java.io/input-stream))}))

(deftest upload-test
  (let [in-mem-fs  (atom {})
        app        (wedding/wedding-app {:auth         (tu/authenticated-backend)
                                         :access-rules wedding/access-rules
                                         :storage      (memory/map->MemFS {:store in-mem-fs})})
        mp-request (fn []
                     "Needs to be a fn so that the InputStream isn't consumed twice"
                     (assemble-multipart "my boundary here"
                                         [{:part-name    "file-contents"
                                           :file-name    "lock.svg"
                                           :content-type "image/svg+xml"
                                           :content      (-> "public/images/lock.svg"
                                                             clojure.java.io/resource
                                                             slurp)}]))]
    (is (match? {:status 401}
                (app (u/deep-merge {:request-method :post
                                    :uri            "/media/"}
                                   (mp-request)))))

    (is (match? {:status 201}
                (app (u/deep-merge {:headers        (tu/auth-header ["wedding"])
                                    :request-method :post
                                    :uri            "/media/"}
                                   (mp-request)))))

    (is (match? {"media" {"lock.svg" {:name     "lock.svg"
                                      :path     "media/lock.svg"
                                      :content  tu/file-input-stream?
                                      :metadata {:filename     "lock.svg"
                                                 :content-type "image/svg+xml"}}}}
                @in-mem-fs))))


(comment
  (require '[clj-http.client :as http])

  (println (:body (assemble-multipart "OZqYohSB93zIWImnnfy2ekkaK8I_BDbVmtiTi"
                                      [{:part-name    "file-contents"
                                        :file-name    "lock.svg"
                                        :content-type "image/svg+xml"
                                        :content      (-> "public/images/lock.svg"
                                                          clojure.java.io/resource
                                                          slurp)}
                                       {:part-name    "file-contents"
                                        :file-name    "lock.svg"
                                        :content-type "image/svg+xml"
                                        :content      (-> "public/images/lock.svg"
                                                          clojure.java.io/resource
                                                          slurp)}])))

  ;; 2021-09-04: This is working - need to make sure the actual index.html can
  ;; make the same request
  (http/request {:scheme      "http"
                 :server-name "caheriaguilar.and.andrewslai.com.localhost"
                 :server-port "5000"
                 :method      :put
                 :uri         "/media/lock.svg"
                 :multipart   [{:name "name" :content "lock.svg"}
                               {:name "content-type" :content "image/svg+xml"}
                               {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                               {:name    "lock.svg"
                                :content (-> "public/images/lock.svg"
                                             clojure.java.io/resource
                                             clojure.java.io/input-stream)}]})



  )
