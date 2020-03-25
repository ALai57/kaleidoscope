(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.mock :as mock-db]
            [andrewslai.clj.utils :refer [parse-response-body]]
            [andrewslai.clj.test-utils :refer :all]
            [clojure.test :refer [testing is]]
            [ring.mock.request :as mock]))

(defsitetest ping-test
  (testing "Ping works properly"
    (is (= #{:sha :service-status} (-> (mock/request :get "/ping")
                                       h/app
                                       parse-response-body
                                       keys
                                       set)))))

(defsitetest home-test
  (testing "Index page works properly"
    (is (= 200 (-> (mock/request :get "/")
                   h/app
                   :status)))))

(defsitetest get-full-article-test
  (with-captured-input-as captured-input capture-fn
    (testing "Can retrieve a full article"
      (with-redefs [mock-db/get-full-article capture-fn]
        (let [endpoint "test-article"

              {:keys [status body] :as response}
              (->> endpoint
                   (str "/get-article/thoughts/")
                   (mock/request :get)
                   h/app)]
          (is (= 200 status))
          (is (= [[endpoint]] @captured-input)))))))
