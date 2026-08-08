(ns kaleidoscope.api.curation.vet
  "One LLM call scoring a fetched/parsed resource against a data-driven
  rubric: each rubric dimension becomes a scored 0-10 axis, plus a boolean
  identity gate (:matches?) — a live page that is the wrong resource fails
  identity regardless of how good its content looks. The prompt is generated
  FROM `(:dimensions rubric)`, so adding/renaming/dropping a dimension
  changes what gets scored with no deploy.

  vet does NOT combine dimension scores into a composite or apply a
  pass-line — that's select-shelf's job (aggregate + threshold, pure, over
  the persisted :scores). This ns sets :composite to a 0.0 placeholder so
  the Verdict schema is satisfiable; select-shelf recomputes and ignores it."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaleidoscope.models.curation :as m]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.workflows.llm-executor :as llm-executor]
            [malli.core :as malli]
            [taoensso.timbre :as log]))

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private default-max-tokens
  2048)

(def ^:private unparseable-verdict
  "A malformed/unparseable vet response DROPS the candidate rather than
  throwing — matches/scores are both empty/false so classify (vetting.clj)
  treats it as an identity miss (\"off-taste\"), never a shelved item."
  {:matches? false :scores {} :composite 0.0 :reason "unparseable vet output"})

(defn- render-dimensions
  "Render the rubric's dimensions into the scored-axis block the vet prompt
  enumerates. Score keys in the model's response must exactly match these
  ids — this is the data-driven surface: a rubric edit changes what gets
  scored without touching this code."
  [dimensions]
  (str/join "\n"
            (map (fn [{:keys [id label description]}]
                   (format "- %s (%s): %s" (name id) label description))
                 dimensions)))

(defn- render-content
  "Render the parsed Content map (title/author/text/available?) into the
  block the model vets against."
  [{:keys [title author text available?]}]
  (str "<fetched_content>\n"
       (when title (str "Title: " title "\n"))
       (when author (str "Author: " author "\n"))
       (when (false? available?)
         "NOTE: this resource is reported unavailable (e.g. removed or dead media) — this is normally an identity/availability failure unless the text below proves otherwise.\n")
       "\n"
       (if (str/blank? text) "(no text extracted)" text)
       "\n</fetched_content>"))

(defn- build-body [content taste rubric config]
  {:model      default-model
   :max_tokens (or (:max-tokens config) default-max-tokens)
   :system     agents/librarian-vet-system-prompt
   :messages   [{:role "user"
                 :content (str (agents/format-taste-profile-context taste)
                                "\n\n<vetting_rubric>\n"
                                (render-dimensions (:dimensions rubric))
                                "\n</vetting_rubric>\n\n"
                                (render-content content))}]})

(defn- text-blocks [content]
  (keep (fn [block] (when (= "text" (:type block)) (:text block))) content))

(defn- normalize-verdict
  "matches -> :matches?, est_time -> :est-time; :scores/:why keys are already
  keyword (json/decode ... true keywordizes nested map keys too), so the
  dimension ids the model echoed back land as keywords with no extra work.
  :composite is always the 0.0 placeholder — select-shelf owns the real
  aggregate."
  [parsed]
  (-> parsed
      (set/rename-keys {:matches :matches? :est_time :est-time})
      (assoc :composite 0.0)))

(defn- parse-verdict
  "Parse the vet response's text block(s) into a Verdict. Returns
  unparseable-verdict on any parse exception or Malli validation failure —
  a malformed vet drops the candidate rather than throwing."
  [content]
  (try
    (let [text    (str/join "\n" (text-blocks content))
          parsed  (json/decode (llm-executor/extract-json text) true)
          verdict (normalize-verdict parsed)]
      (if (malli/validate m/Verdict verdict)
        verdict
        (do (log/warnf "Vet output failed Verdict validation: %s" (pr-str verdict))
            unparseable-verdict)))
    (catch Exception e
      (log/warnf "Failed to parse vet verdict: %s" (.getMessage e))
      unparseable-verdict)))

(defn vet
  "Score `content` (a parsed Content map) against `taste` and `rubric` via one
  LLM call. Returns a Verdict: :scores keyed by each `(:dimensions rubric)`
  id (0-10), :matches? the identity hard gate, :composite always the 0.0
  placeholder (select-shelf recomputes the real composite from :scores
  against the rubric's weights — never computed here). Never throws: any
  exception or unparseable/invalid model output yields
  {:matches? false :scores {} :composite 0.0 :reason \"unparseable vet output\"}."
  [content taste rubric config]
  (try
    (let [api-key  (:api-key config)
          response (llm-executor/post-anthropic-sync api-key (build-body content taste rubric config))]
      (parse-verdict (:content response)))
    (catch Exception e
      (log/warnf "Vet failed: %s" (.getMessage e))
      unparseable-verdict)))
