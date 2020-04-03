(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.mock :as mock-db]
            [andrewslai.clj.utils :refer [parse-response-body]]
            [andrewslai.clj.test-utils :refer :all]
            [clojure.test :refer [testing is]]
            [ring.mock.request :as mock]))

(def test-app (h/app {}))

(defsitetest ping-test
  (testing "Ping works properly"
    (let [response (-> (mock/request :get "/ping")
                       test-app)]
      (is (= 200 (:status response)))
      (is (= #{:sha :service-status} (-> response
                                         parse-response-body
                                         keys
                                         set))))))

(defsitetest home-test
  (testing "Index page works properly"
    (is (= 200 (-> (mock/request :get "/")
                   test-app
                   :status)))))

(defsitetest get-full-article-test
  (testing "get-article endpoint returns an article data structure"
    (let [response (->> "/articles/test-article"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:article
               :article-name}
             (set (keys (parse-response-body response)))))))
  (testing "Persistence protocol receives correct input"
    (with-captured-input-as captured-input capture-fn
      (with-redefs [mock-db/get-full-article
                    (partial capture-fn :get-full-article)]
        (let [article-name "test-article"

              response (->> article-name
                            (str "/articles/")
                            (mock/request :get)
                            test-app)]
          (is (= 200 (:status response)))
          (is (= [{:get-full-article [article-name]}] @captured-input)))))))

(defsitetest get-all-articles-test
  (testing "get-all-articles endpoint returns all articles"
    (let [response (->> "/articles"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= 3 (count (parse-response-body response)))))))

(defsitetest get-resume-info-test
  (testing "get-resume-info endpoint returns an resume-info data structure"
    (let [response (->> "/get-resume-info"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-response-body response))))))))
