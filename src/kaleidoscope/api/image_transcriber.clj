(ns kaleidoscope.api.image-transcriber
  "Transcribe uploaded recipe photos into plain text. OCR only — it does not
  decide whether the text is a recipe or impose structure; interpretation is the
  scraper's shared PARSE (`parse-text`). One protocol, two impls on the OCR-quality
  axis: Claude vision (default) and Google Cloud Vision (committed follow-up). No
  image bytes are retained. See plans/2026-07-12-recipe-photo-import/DESIGN.md."
  (:require [kaleidoscope.workflows.llm-executor :as llm]
            [taoensso.timbre :as log])
  (:import (java.util Base64)))

(defprotocol ImageTranscriber
  (transcribe [this images]
    "images: [{:content-type string :bytes byte-array}] ->
    {:transcript string :technique keyword :llm-calls [{:purpose :model :request :response}]}.
    The transcript may be empty; the interpretation stage renders the no-recipe verdict."))

(def ^:private transcribe-model "claude-haiku-4-5")

(def ^:private transcribe-prompt
  "You are given one or more images of a single recipe (a cookbook page or a screenshot). Transcribe ALL text you can read, verbatim, in natural reading order, concatenating multiple images in the order given. Do not interpret, summarize, translate, reformat, or add commentary. Preserve ingredient lines and step text exactly. Output only the transcribed text.")

(defn- ->image-block
  [{:keys [content-type bytes]}]
  {:type   "image"
   :source {:type       "base64"
            :media_type content-type
            :data       (.encodeToString (Base64/getEncoder) bytes)}})

(defrecord ClaudeVisionTranscriber [api-key model]
  ImageTranscriber
  (transcribe [_ images]
    (log/infof "Transcribing %d image(s) via Claude vision" (count images))
    (let [model'   (or model transcribe-model)
          request  {:model      model'
                    :max_tokens 4096
                    :system     transcribe-prompt
                    :messages   [{:role    "user"
                                  :content (conj (mapv ->image-block images)
                                                 {:type "text" :text "Transcribe the recipe in these images."})}]}
          response (llm/post-anthropic-sync api-key request)
          text     (-> response :content first :text)]
      {:transcript (or text "")
       :technique  :claude-vision
       :llm-calls  [{:purpose :transcribe :model model' :request request :response response}]})))

;; Committed second implementation (handwriting / dense layouts). Wired in
;; init.env so the axis of variation is real; the GCP call is a follow-up.
(defrecord GoogleVisionTranscriber [api-key]
  ImageTranscriber
  (transcribe [_ _images]
    (throw (UnsupportedOperationException. "google-vision transcriber not yet implemented"))))

;; Local dev / tests without ANTHROPIC_API_KEY: canned transcript.
(defrecord MockTranscriber [transcript]
  ImageTranscriber
  (transcribe [_ _images]
    {:transcript transcript :technique :claude-vision :llm-calls []}))

(defn make-claude-vision-transcriber [{:keys [api-key model]}]
  (->ClaudeVisionTranscriber api-key model))

(defn make-google-vision-transcriber [{:keys [api-key]}]
  (->GoogleVisionTranscriber api-key))

(defn make-mock-transcriber
  ([] (make-mock-transcriber "Chana Masala\n\nIngredients:\n2 cups chickpeas\n1 tbsp flour\n\nSteps:\nSoak overnight.\nCook until tender."))
  ([transcript] (->MockTranscriber transcript)))
