(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.mock :as mock-db]
            [andrewslai.clj.utils :refer [parse-response-body]]
            [clojure.test :refer [deftest testing is]]
            [ring.mock.request :as mock]))

(deftest ping-test
  (testing "Ping works properly"
    (is (= #{:sha :service-status} (-> (mock/request :get "/ping")
                                       h/app
                                       parse-response-body
                                       keys
                                       set)))))

(deftest home-test
  (testing "Index page works properly"
    (is (= 200 (-> (mock/request :get "/")
                   h/app
                   :status)))))

;; Failing because it uses the wrong environment variables when testing
;;  look into mounting components...
(deftest get-full-article-test
  (let [captured-input (atom [])]
    (testing "Can retrieve a full article"
      (with-redefs
        [mock-db/get-full-article
         (fn [article-name]
           (println "HELLO!!!!")
           (swap! captured-input conj article-name))]
        (let [{:keys [status body] :as response}
              (h/app (mock/request :get "/get-article/thoughts/test-article"))]
          (is (= 200 status))
          (is (= nil @captured-input)))))))

