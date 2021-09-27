(ns andrewslai.clj.http-api.wedding-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.http-api.static-content :as sc]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.persistence.memory :as memory]
            [andrewslai.clj.test-utils :as tu]
            [andrewslai.clj.utils.core :as util]
            [clj-http.client :as http]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures and setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(def example-fs
  "An in-memory filesystem used for testing"
  {"media" {"afile" (memory/file {:name     "afile"
                                  :content  {:qux :quz}
                                  :metadata {}})
            "adir"  {"anotherfile" (memory/file {:name     "afile"
                                                 :content  {:qux :quz}
                                                 :metadata {}})}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Access rules
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest access-rules-test
  (are [description expected components]
    (testing description
      (let [handler (wedding/wedding-app components)]
        (is (match? expected (handler (mock/request :get "/ping"))))))

    "Public-access routes can be reached by unauthenticated user"
    {:status 200} {:access-rules tu/public-access}

    "Restricted-access routes can be reached by authorized user"
    {:status 200} {:access-rules (tu/restricted-access "wedding")
                   :auth         (auth/always-authenticated-backend {:realm_access {:roles ["wedding"]}})}

    "Restricted-access routes cannot be reached by unauthorized user"
    {:status 401} {:access-rules (tu/restricted-access "wedding")
                   :auth         (auth/always-authenticated-backend {:realm_access {:roles ["not-wedding-role"]}})}

    "Restricted-access routes cannot be reached by unauthenticated user"
    {:status 401} {:access-rules (tu/restricted-access "wedding")
                   :auth         (tu/unauthenticated-backend)}))

(deftest access-rule-configuration-test
  (are [description expected request]
    (testing description
      (let [handler (wedding/wedding-app {:access-rules wedding/access-rules
                                          :auth         (tu/unauthenticated-backend)})]
        (is (match? expected (handler request)))))

    "GET `/ping` is publically accessible"
    {:status 200} (mock/request :get "/ping")

    "GET `/media/` is not publically accessible"
    {:status 401} (mock/request :get "/media/")

    "POST `/media/` is not publically accessible"
    {:status 401} (mock/request :post "/media/")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP API test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest static-content-test
  (let [in-mem-fs (atom example-fs)
        app       (-> {:auth         (tu/authenticated-backend)
                       :access-rules tu/public-access
                       :storage      (memory/map->MemFS {:store in-mem-fs})}
                      (wedding/wedding-app)
                      (tu/wrap-clojure-response))]
    (is (match? {:status  200
                 :headers {"Cache-Control" sc/no-cache}
                 :body    [{:name "afile"} {:name "adir" :type "directory"}]}
                (app {:request-method :get
                      :uri            "/media/"
                      :headers        (tu/auth-header ["wedding"])})))))

(deftest upload-test
  (let [in-mem-fs  (atom {})
        app        (wedding/wedding-app {:auth         (tu/authenticated-backend)
                                         :access-rules tu/public-access
                                         :storage      (memory/map->MemFS {:store in-mem-fs})})]

    (is (match? {:status 201}
                (app (util/deep-merge {:headers        (tu/auth-header ["wedding"])
                                       :request-method :post
                                       :uri            "/media/"}
                                      (tu/assemble-multipart "my boundary here"
                                                             [{:part-name    "file-contents"
                                                               :file-name    "lock.svg"
                                                               :content-type "image/svg+xml"
                                                               :content      (-> "public/images/lock.svg"
                                                                                 clojure.java.io/resource
                                                                                 slurp)}])))))

    (is (match? {"media" {"lock.svg" {:name     "lock.svg"
                                      :path     "media/lock.svg"
                                      :content  tu/file-input-stream?
                                      :metadata {:filename     "lock.svg"
                                                 :content-type "image/svg+xml"}}}}
                @in-mem-fs))))


(comment
  (require '[clj-http.client :as http])

  (println (:body (tu/assemble-multipart "OZqYohSB93zIWImnnfy2ekkaK8I_BDbVmtiTi"
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
