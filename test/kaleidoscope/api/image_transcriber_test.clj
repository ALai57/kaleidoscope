(ns kaleidoscope.api.image-transcriber-test
  (:require [kaleidoscope.api.image-transcriber :as transcriber]
            [kaleidoscope.workflows.llm-executor :as llm]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(defn- img [s] {:content-type "image/jpeg" :bytes (.getBytes ^String s "UTF-8")})

(deftest claude-vision-transcribe-test
  (testing "one image block per image + a trailing text block; transcript returned verbatim"
    (let [captured (atom nil)]
      (with-redefs [llm/post-anthropic-sync
                    (fn [_ req] (reset! captured req)
                      {:content [{:text "Chana Masala\n2 cups chickpeas\nCook."}]})]
        (let [result (transcriber/transcribe
                      (transcriber/make-claude-vision-transcriber {:api-key "sk-test"})
                      [(img "page1") (img "page2")])]
          (is (match? {:transcript "Chana Masala\n2 cups chickpeas\nCook."
                       :technique  :claude-vision
                       :llm-calls  [{:purpose :transcribe :model "claude-haiku-4-5"}]}
                      result))
          (is (match? {:messages [{:content [{:type "image"} {:type "image"} {:type "text"}]}]}
                      @captured)))))))

(deftest mock-transcriber-returns-canned-text-test
  (is (match? {:transcript "hi" :technique :claude-vision}
              (transcriber/transcribe (transcriber/make-mock-transcriber "hi") [(img "x")]))))

(deftest google-vision-not-yet-implemented-test
  (is (thrown? UnsupportedOperationException
               (transcriber/transcribe (transcriber/make-google-vision-transcriber {:api-key "k"}) [(img "x")]))))
