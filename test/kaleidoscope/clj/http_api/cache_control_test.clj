(ns kaleidoscope.clj.http-api.cache-control-test
  (:require [kaleidoscope.clj.http-api.cache-control :as cc]
            [kaleidoscope.clj.test-main :as tm]
            [clojure.test :refer [are deftest is use-fixtures]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(deftest cache-control-header-test
  (is (= cc/revalidate-0s (cc/cache-control-header "Hello.html")))
  (is (= cc/revalidate-0s (cc/cache-control-header "hello/")))
  (is (= cc/cache-30d (cc/cache-control-header "Hello.png")))
  (is (= cc/cache-30d (cc/cache-control-header "hello.svg")))
  (is (= cc/cache-30d (cc/cache-control-header "hello.jpg"))))

(deftest cache-control-with-success-response-test
  (are [expected url]
    (= {:status  200
        :headers {"Cache-Control" expected}}
       (cc/cache-control {:status 200} url))

    cc/revalidate-0s  "hello.html"
    cc/revalidate-0s  "hello/"
    cc/cache-30d "hello.png"
    cc/cache-30d "hello.jpg"
    ))

(deftest cache-control-with-non-200-test
  (is (= {:status 400}
         (cc/cache-control {:status 400} "Hello.html"))))
