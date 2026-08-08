(ns kaleidoscope.api.curation.discover-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.discover :as discover]
            [kaleidoscope.models.curation :as m]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.workflows.llm-executor :as llm]
            [malli.core :as malli]))

(def ^:private real-urls
  ["https://example.com/real/1"
   "https://example.com/real/2"
   "https://example.com/real/3"])

(def ^:private invented-url
  "https://example.com/invented/does-not-exist")

(defn- candidate-json [url title]
  {:kind "article" :title title :source "example.com" :url url
   :est_time "10 min" :why "on-taste"})

(def ^:private candidates-payload
  {:candidates [(candidate-json (nth real-urls 0) "Real one")
                (candidate-json (nth real-urls 1) "Real two")
                (candidate-json (nth real-urls 2) "Real three")
                (candidate-json invented-url "Invented")]})

(def ^:private mock-response
  {:stop_reason "end_turn"
   :content [{:type "web_search_tool_result"
              :tool_use_id "toolu_1"
              :content (mapv (fn [u] {:type "web_search_result" :url u :title u}) real-urls)}
             {:type "text" :text (json/encode candidates-payload)}]})

(deftest discover-drops-ungrounded-candidates
  (testing "only candidates whose url appeared in a web_search result survive"
    (let [captured-body (atom nil)]
      (with-redefs [llm/post-anthropic-sync (fn [_k body] (reset! captured-body body) mock-response)]
        (let [taste  {:keywords ["deep work"] :avoid ["clickbait"]}
              config {:api-key "k" :shelf-size 3 :over-discover-factor 1.0}
              result (discover/discover taste config)]
          (is (= 3 (count result)))
          (is (= (set real-urls) (set (map #(get-in % [:ref :url]) result))))
          (is (not (contains? (set (map #(get-in % [:ref :url]) result)) invented-url)))
          (doseq [c result]
            (is (malli/validate m/Candidate c)))
          (testing "request body uses snake_case :max_tokens on the wire"
            (is (contains? @captured-body :max_tokens))
            (is (not (contains? @captured-body :max-tokens)))))))))

(deftest discover-returns-empty-on-exception
  (testing "failures are data — an exception yields [] rather than throwing"
    (with-redefs [llm/post-anthropic-sync (fn [_k _body] (throw (ex-info "boom" {})))]
      (is (= [] (discover/discover {} {:api-key "k"}))))))

(deftest discover-returns-empty-on-unparseable-output
  (testing "no candidate JSON in the text block -> []"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_k _body]
                    {:stop_reason "end_turn"
                     :content [{:type "text" :text "I couldn't find anything relevant."}]})]
      (is (= [] (discover/discover {} {:api-key "k"}))))))

(deftest format-taste-profile-context-renders-avoid
  (testing "avoid terms are rendered in the taste profile block"
    (let [rendered (agents/format-taste-profile-context
                     {:keywords ["deep work"] :avoid ["clickbait" "listicles"]})]
      (is (re-find #"(?i)avoid" rendered))
      (is (re-find #"clickbait" rendered)))))

(deftest format-taste-profile-context-renders-directives
  (testing "active directives compose into a separate <directives> block"
    (let [rendered (agents/format-taste-profile-context
                     {:keywords ["deep work"] :directives ["Prefer longer essays over short posts"]})]
      (is (re-find #"<directives>" rendered))
      (is (re-find #"Prefer longer essays" rendered)))))
