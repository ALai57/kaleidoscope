(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres-test]
            [andrewslai.clj.persistence.core :refer [ArticlePersistence]]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.utils :refer [parse-response-body]]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]))

(def test-db
  (atom {:articles [{:title "Test article",
                     :article_tags "thoughts",
                     :timestamp "2019-11-07T00:48:08.136082000-00:00",
                     :author "Andrew Lai", :article_url "test-article",
                     :article_id 10}]
         :metadata [{:title "Test article",
                     :article_tags "thoughts",
                     :timestamp #inst "2019-11-07T00:48:08.136082000-00:00",
                     :author "Andrew Lai",
                     :article_url "test-article",
                     :article_id 10}]
         :content [{:article_id 10,
                    :content
                    "<div>\n  <font color=\"#ce181e\"><font face=\"Ubuntu Mono\"><font size=\"5\"><b>A\n          basic test</b></font></font></font><p />\n  <p style=\"color:red\">Many of the</p>\n  <p>Usually, that database</p>\n</div>",
                    :dynamicjs []}]
         :resume-info {:skills [{:id 1,
                                 :name "Periscope Data",
                                 :url "",
                                 :image_url "images/periscope-logo.svg",
                                 :description "",
                                 :skill_category "Analytics Tool"}]
                       :projects []
                       :organizations [{:id 1,
                                        :name "HELIX",
                                        :url "https://helix.northwestern.edu",
                                        :image_url "images/nu-helix-logo.svg",
                                        :description "Science Outreach Magazine"}]}}))

(def test-app (h/wrap-middleware h/bare-app {:db test-db}))

(deftest get-all-articles-test
  (testing "get-all-articles endpoint returns all articles"
    (let [response (->> "/articles"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= (:articles @test-db)
             (parse-response-body response))))))

(deftest get-full-article-test
  (testing "get-article endpoint returns an article data structure"
    (let [response (->> "/articles/test-article"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:article
               :article-name}
             (set (keys (parse-response-body response))))))))

(deftest get-resume-info-test
  (testing "get-resume-info endpoint returns an resume-info data structure"
    (let [response (->> "/get-resume-info"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-response-body response))))))))
