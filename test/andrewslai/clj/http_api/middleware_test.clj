(ns andrewslai.clj.http-api.middleware-test
  (:require [amazonica.aws.s3 :as s3]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as sut]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.persistence.filesystem.url-utils :as url-utils]
            [andrewslai.clj.test-utils :as tu]
            [biiwide.sandboxica.alpha :as sandbox]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [ring.util.response :as response]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest resources-from-classpath-test
  "The Thread's default classloader should be unable to load a resource from a
  location that is not on the classpath. We can make a custom classloader, and
  using that, we can customize the Classpath to load arbitrary files."
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]
    (are [pred opts]
      (pred (response/resource-response path opts))

      nil? {:loader (.getContextClassLoader (Thread/currentThread))}
      map? {:loader (tu/make-loader tu/TEMP-DIRECTORY)})))

(deftest files-response-test
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (are [pred path options]
      (pred (response/file-response path options))

      nil? (.getName tmpfile) {:root ""}
      map? (.getName tmpfile) {:root (.getAbsolutePath tmpdir)})))

(deftest s3-response-test
  (let [bucket   "andrewslai-wedding"
        endpoint "media/"]
    (sandbox/with (comp (sandbox/just
                         (s3/list-objects-v2
                          ([req]
                           (or (is (match? {:prefix      endpoint
                                            :bucket-name bucket}
                                           req))
                               (throw (Exception. "Invalid inputs")))
                           {:bucket-name     bucket
                            :common-prefixes []
                            :key-count       1}
                           )))
                        sandbox/always-fail)
                  (are [expected loader]
                    (is (match? expected (response/resource-response endpoint
                                                                     {:loader loader})))

                    nil?                   (.getContextClassLoader (Thread/currentThread))
                    {:status 200 :body ()} (->> (s3-storage/map->S3 {:bucket bucket
                                                                     :creds  {:profile "none"
                                                                              :endpoint "dummy"}})
                                                (url-utils/filesystem-loader))))))

(deftest standard-stack-test
  (let [captured-request (atom nil)
        app              (sut/standard-stack (fn [req]
                                               (reset! captured-request req)
                                               {:status 200
                                                :body   {:foo "bar"}}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    (json/generate-string {:foo "bar"})}
                (app (mock/request :get "/"))))
    (is (match? {:uri        "/index.html"
                 :request-id string?}
                @captured-request))))

(deftest auth-stack-happy-path-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                         (tu/restricted-access "myrole"))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 200
                 :body   {:foo "bar"}}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (match? {:identity {:realm_access {:roles ["myrole"]}}}
                @captured-request))))

(deftest auth-stack-wrong-role-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                         (tu/restricted-access "wrongrole"))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 401}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (nil? (:identity @captured-request)))))
