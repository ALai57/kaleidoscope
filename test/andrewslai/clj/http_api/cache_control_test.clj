(ns andrewslai.clj.http-api.cache-control-test
  (:require [andrewslai.clj.http-api.cache-control :as cc]
            [clojure.test :refer [are deftest is use-fixtures]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest cache-control-header-test
  (is (= cc/no-cache  (cc/cache-control-header "Hello.html")))
  (is (= cc/no-cache  (cc/cache-control-header "hello/")))
  (is (= cc/cache-30d (cc/cache-control-header "Hello.png")))
  (is (= cc/cache-30d (cc/cache-control-header "hello.svg")))
  (is (= cc/cache-30d (cc/cache-control-header "hello.jpg"))))

(deftest cache-control-with-success-response-test
  (are [expected url]
    (= {:status  200
        :headers {"Cache-Control" expected}}
       (cc/cache-control url {:status 200}))

    cc/no-cache  "hello.html"
    cc/no-cache  "hello/"
    cc/cache-30d "hello.png"
    cc/cache-30d "hello.jpg"
    ))

(deftest cache-control-with-non-200-test
  (is (= {:status 400}
         (cc/cache-control "Hello.html" {:status 400}))))
