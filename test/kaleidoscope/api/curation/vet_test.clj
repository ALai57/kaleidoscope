(ns kaleidoscope.api.curation.vet-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.vet :as vet]
            [kaleidoscope.models.curation :as m]
            [kaleidoscope.workflows.llm-executor :as llm]
            [malli.core :as malli]))

(def ^:private rubric
  (get m/default-rubric "standard"))

(def ^:private content
  {:title "Deep Work" :author "Cal Newport" :text "Focused, undistracted effort..." :available? true})

(def ^:private taste
  {:keywords ["deep work" "focus"] :trusted-sources ["calnewport.com"]})

(def ^:private verdict-payload
  {:matches   true
   :scores    {:relevance 8 :depth 7 :taste-fit 9 :novelty 6}
   :why       {:relevance "on the reader's stated intent"
               :depth "substantive treatment of the topic"
               :taste-fit "matches keywords and trusted source"
               :novelty "not something the reader has seen"}
   :est_time  "18 min"
   :reason    "Directly on-topic and substantive."})

(def ^:private mock-response
  {:content [{:type "text" :text (str "```json\n" (json/encode verdict-payload) "\n```")}]})

(deftest vet-scores-each-rubric-dimension
  (testing "vet returns a Verdict whose :scores keys are exactly the rubric dimension ids"
    (let [captured-body (atom nil)]
      (with-redefs [llm/post-anthropic-sync (fn [_k body] (reset! captured-body body) mock-response)]
        (let [verdict (vet/vet content taste rubric {:api-key "k"})]
          (is (malli/validate m/Verdict verdict))
          (is (= #{:relevance :depth :taste-fit :novelty} (set (keys (:scores verdict)))))
          (is (boolean? (:matches? verdict)))
          (is (true? (:matches? verdict)))
          (is (number? (:composite verdict)))
          (is (= 0.0 (:composite verdict)) "vet never computes composite — select-shelf does")
          (testing "request body uses snake_case :max_tokens on the wire"
            (is (contains? @captured-body :max_tokens))
            (is (not (contains? @captured-body :max-tokens)))))))))

(deftest vet-prompt-enumerates-rubric-dimensions
  (testing "the user message is generated FROM the rubric's dimensions"
    (let [captured-body (atom nil)]
      (with-redefs [llm/post-anthropic-sync (fn [_k body] (reset! captured-body body) mock-response)]
        (vet/vet content taste rubric {:api-key "k"})
        (let [user-content (get-in @captured-body [:messages 0 :content])]
          (doseq [{:keys [id label description]} (:dimensions rubric)]
            (is (str/includes? user-content (name id)))
            (is (str/includes? user-content label))
            (is (str/includes? user-content description))))))))

(deftest vet-returns-drop-verdict-on-unparseable-output
  (testing "malformed model output drops the candidate rather than throwing"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_k _body] {:content [{:type "text" :text "I'm not sure, sorry."}]})]
      (let [verdict (vet/vet content taste rubric {:api-key "k"})]
        (is (= false (:matches? verdict)))
        (is (= {} (:scores verdict)))
        (is (malli/validate m/Verdict verdict))))))

(deftest vet-returns-drop-verdict-on-exception
  (testing "an exception from post-anthropic-sync is data, not a throw"
    (with-redefs [llm/post-anthropic-sync (fn [_k _body] (throw (ex-info "boom" {})))]
      (let [verdict (vet/vet content taste rubric {:api-key "k"})]
        (is (= false (:matches? verdict)))
        (is (= {} (:scores verdict)))))))
