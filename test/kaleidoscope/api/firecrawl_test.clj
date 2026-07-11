(ns kaleidoscope.api.firecrawl-test
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.firecrawl :as firecrawl]
            [matcher-combinators.test :refer [match?]]))

(def ^:private fetcher (firecrawl/make-firecrawl-fetcher {:api-key "fc-test"}))

(deftest returns-raw-html-on-success-test
  (testing "a 200 with data.rawHtml returns that HTML (rawHtml preserves JSON-LD)"
    (with-redefs [http/post (fn [_url _opts]
                              {:status 200
                               :body   (json/encode {:success true
                                                     :data    {:rawHtml "<html><body>rendered</body></html>"}})})]
      (is (= "<html><body>rendered</body></html>"
             (firecrawl/fetch-rendered fetcher "http://example.com/r"))))))

(deftest render-failed-on-non-200-test
  (testing "a non-200 response throws :render-failed (propagates for Bugsnag, not a 422 reason)"
    (with-redefs [http/post (fn [_url _opts] {:status 500 :body "upstream error"})]
      (is (match? {:type :scrape :reason :render-failed :status 500}
                  (try (firecrawl/fetch-rendered fetcher "http://example.com/r")
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(deftest render-failed-when-raw-html-missing-test
  (testing "a 200 without data.rawHtml throws :render-failed rather than returning nil"
    (with-redefs [http/post (fn [_url _opts]
                              {:status 200 :body (json/encode {:success true :data {}})})]
      (is (match? {:reason :render-failed}
                  (try (firecrawl/fetch-rendered fetcher "http://example.com/r")
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))
