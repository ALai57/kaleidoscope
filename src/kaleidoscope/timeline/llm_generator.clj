(ns kaleidoscope.timeline.llm-generator
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.models.recipes :as models]
            [kaleidoscope.timeline.protocol :as protocol]
            [malli.core :as m]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private anthropic-messages-url "https://api.anthropic.com/v1/messages")
(def ^:private anthropic-version "2023-06-01")
(def ^:private default-model "claude-opus-4-6")
(def ^:private connect-timeout (Duration/ofSeconds 10))
(def ^:private request-timeout (Duration/ofSeconds 60))

(defn- post-anthropic [api-key body-map]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create anthropic-messages-url))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" anthropic-version)
                    (.timeout request-timeout)
                    (.POST (HttpRequest$BodyPublishers/ofString (json/encode body-map)))
                    (.build))
        client  (-> (HttpClient/newBuilder) (.connectTimeout connect-timeout) (.build))
        resp    (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode resp))
      (log/errorf "Anthropic timeline error %d: %s" (.statusCode resp) (.body resp))
      (throw (ex-info "Anthropic API error" {:type :generation :status (.statusCode resp)})))
    (json/decode (.body resp) true)))

(defn parse-segment-response
  "Parse Claude's JSON into {:components …}. Throws ex-info {:type :generation}
  on malformed output."
  [text]
  (let [clean (-> (or text "")
                  str/trim
                  (str/replace #"(?s)^```(?:json)?\s*" "")
                  (str/replace #"\s*```$" "") str/trim)]
    (try
      (let [parsed (json/decode clean true)]
        (when-not (m/validate models/TimelineProposal parsed)
          (throw (ex-info "invalid timeline proposal" {:type :generation})))
        parsed)
      (catch com.fasterxml.jackson.core.JsonProcessingException _
        (throw (ex-info "malformed timeline JSON" {:type :generation}))))))

(defn- build-prompt [recipe changed-ids cached]
  (str "You schedule cooking. For each recipe COMPONENT, segment its steps into "
       "phases (a contiguous group of steps with one duration). Output ONLY JSON: "
       "{\"components\":[{\"name\":<component-id>,\"phases\":[{\"id\":\"<component-id>/<label>\","
       "\"label\":<unique-within-component>,\"kind\":\"active\"|\"passive\",\"steps\":[<int step indices>],"
       "\"estimate\":<minutes int>,\"deps\":[\"<phase id>\"]}]}]}. "
       "\"passive\" = unattended (marinate/rise/rest/bake/simmer); deps may cross components. "
       "COMPONENTS MARKED CHANGED must be re-segmented: " (pr-str (vec changed-ids)) ". "
       "For UNCHANGED components reproduce the cached phases exactly. "
       "Cached: " (json/encode cached) "\n\nRecipe: " (json/encode (:content recipe))))

(defrecord LlmGenerator [api-key model]
  protocol/ITimelineGenerator
  (segment [_this recipe changed-ids cached]
    (let [body {:model      (or model default-model)
                :max_tokens 4096
                :messages   [{:role "user" :content (build-prompt recipe changed-ids cached)}]}
          text (-> (post-anthropic api-key body) :content first :text)]
      (parse-segment-response text))))

(defn make-llm-generator [{:keys [api-key model]}]
  (->LlmGenerator api-key model))
