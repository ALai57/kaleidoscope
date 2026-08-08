(ns kaleidoscope.api.curation.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.providers :as p]))

(deftest parse-dispatches-on-host
  (testing "default readability extracts text + title from html"
    (let [c (p/parse {:final-url "https://blog.example/x"
                       :raw "<html><head><title>Deep Work</title></head><body><p>Focus.</p></body></html>"
                       :content-type "text/html"})]
      (is (= "Deep Work" (:title c)))
      (is (re-find #"Focus" (:text c)))))

  (testing "youtube host extracts availability + title from oEmbed-like html"
    (let [c (p/parse {:final-url "https://www.youtube.com/watch?v=abc123"
                       :raw "<html><head><title>Cool Video - YouTube</title></head><body></body></html>"
                       :content-type "text/html"})]
      (is (true? (:available? c)))
      (is (= "Cool Video" (:title c)))))

  (testing "youtube host without a title marks unavailable"
    (let [c (p/parse {:final-url "https://youtu.be/abc123"
                       :raw "<html><head></head><body>Video unavailable</body></html>"
                       :content-type "text/html"})]
      (is (false? (:available? c)))))

  (testing "goodreads host extracts title + author"
    (let [c (p/parse {:final-url "https://www.goodreads.com/book/show/123"
                       :raw "<html><head><title>Deep Work</title></head><body><span class=\"author\">Cal Newport</span></body></html>"
                       :content-type "text/html"})]
      (is (= "Deep Work" (:title c)))
      (is (= "Cal Newport" (:author c)))
      (is (true? (:available? c))))))
