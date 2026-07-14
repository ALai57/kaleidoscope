(ns kaleidoscope.models.recipes)

;; The single recipe-content value shape. Both the current recipe (`content`)
;; and the immutable scrape (`original-content`) validate against this, so they
;; cannot drift. Sections are the ONLY representation: a simple recipe is one
;; unnamed section. Steps are plain text — HTML rendering belongs to the UI.
(def RecipeSection
  [:map
   [:name        {:optional true} [:maybe :string]] ;; absent/nil ⇒ unnamed
   [:ingredients [:sequential :string]]             ;; verbatim freeform lines
   [:steps       [:sequential :string]]])           ;; plain text, one per step

(def RecipeContent
  [:map
   [:title             :string]
   [:sections          [:sequential {:min 1} RecipeSection]]
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]])

;; ---- Cook timeline (derived; see recipe-cook-timeline DESIGN.md) ----

(def Phase
  [:map
   [:id       :string]                 ;; "{component-id}/{label}"
   [:label    :string]                 ;; unique within its component
   [:kind     [:enum "active" "passive"]]
   [:steps    [:sequential :int]]      ;; indices into the component's steps
   [:estimate :int]                    ;; LLM minutes
   [:deps     [:sequential :string]]   ;; phase ids this phase waits on
   [:start    {:optional true} [:maybe :int]]]) ;; packer output (minutes from t0)

(def TimelineComponent
  [:map
   [:name       :string]               ;; the component-id (lane label)
   [:steps-hash :string]
   [:phases     [:sequential Phase]]])

(def TimelineOverride
  [:map
   [:phase   :string]                  ;; a Phase :id
   [:minutes :int]])

(def Timeline
  [:map
   [:version           :int]
   [:generator-version :int]
   [:generated-at      some?]
   [:total-minutes     :int]
   [:overrides         [:sequential TimelineOverride]]
   [:components        [:sequential TimelineComponent]]])

(def TimelineOverridesRequest
  [:map [:overrides [:sequential TimelineOverride]]])

(def TimelineProposal
  ;; What a generator's `segment` returns (pre-assemble/pack): components carry no
  ;; :steps-hash yet, and phases carry no :start (Phase's :start is optional).
  [:map
   [:components
    [:sequential
     [:map
      [:name   :string]
      [:phases [:sequential Phase]]]]]])

(def RawScrape
  ;; Validated before persistence. Fetch fields are nullable so a pre-fetch
  ;; failure (url) or a photo import still records source-kind + hostname.
  [:map
   [:hostname    :string]
   [:source-kind [:enum "url" "photo"]]
   [:request-url {:optional true} [:maybe :string]]
   [:final-url   {:optional true} [:maybe :string]]
   [:http-status {:optional true} [:maybe :int]]
   [:fetch-tier  {:optional true} [:maybe :string]]
   [:raw-content {:optional true} [:maybe :string]]])

;; The unified PARSE artifact. Both techniques emit this shape; NORMALIZE
;; dispatches on what it carries. `:grouping` (section name + indexes into the
;; flat lists) is present only on the LLM path; `:section-signals` (candidate
;; section names) only on the JSON-LD path.
(def ExtractedFacts
  [:map
   [:title            [:maybe :string]]
   [:ingredients      [:sequential :string]]
   [:steps            [:sequential :string]]
   [:section-signals  [:sequential :string]]
   [:grouping         {:optional true}
    [:maybe [:sequential [:map
                          [:name        {:optional true} [:maybe :string]]
                          [:ingredients [:sequential :int]]
                          [:steps       [:sequential :int]]]]]]
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]
   [:labels            [:sequential :string]]])

(def RecipeLabel
  [:map
   [:id         :uuid]
   [:name       :string]
   [:group-id   {:optional true} [:maybe :uuid]]
   [:group-name {:optional true} [:maybe :string]]])

(def GetRecipeResponse
  [:map
   [:id                :uuid]
   [:recipe-url        :string]
   [:hostname          :string]
   [:content           RecipeContent]
   [:original-content  {:optional true} [:maybe RecipeContent]]
   [:labels            {:optional true} [:sequential RecipeLabel]]
   [:source-url        {:optional true} [:maybe :string]]
   [:author            {:optional true} [:maybe :string]]
   [:public-visibility :boolean]
   [:created-at        some?]
   [:modified-at       some?]
   [:scrape-processing-run-id {:optional true} [:maybe :uuid]]
   [:timeline {:optional true} [:maybe Timeline]]])

(def CreateRecipeRequest
  [:map
   [:content          RecipeContent]
   [:original-content {:optional true} [:maybe RecipeContent]]
   [:recipe-url       {:optional true} :string]
   [:source-url       {:optional true} [:maybe :string]]
   [:label-ids        {:optional true} [:sequential :uuid]]
   [:public-visibility {:optional true} :boolean]
   [:scrape-processing-run-id {:optional true} [:maybe :uuid]]])

(def UpdateRecipeRequest
  [:map
   [:content           {:optional true} RecipeContent]
   [:recipe-url        {:optional true} :string]
   [:source-url        {:optional true} [:maybe :string]]
   [:label-ids         {:optional true} [:sequential :uuid]]
   [:public-visibility {:optional true} :boolean]])

(def ScrapeResult
  [:map
   [:recipe           RecipeContent]
   [:suggested-labels [:sequential :string]]
   [:techniques       [:map
                       [:acquire   :keyword]
                       [:parse     :keyword]
                       [:normalize :keyword]]]
   [:warnings         [:sequential :string]]
   [:scrape-processing-run-id :uuid]])

(def LlmCall
  ;; A stored Anthropic call. `purpose` round-trips from JSONB as a string;
  ;; request/response are stored verbatim. They must stay opaque *and* survive
  ;; response coercion: a bare `:map` has no declared entries, so the router's
  ;; strip-extra-keys transformer empties it to `{}`. `:map-of` isn't subject to
  ;; that stripping, so the full bodies pass through intact.
  [:map
   [:purpose  some?]
   [:model    :string]
   [:request  [:map-of :any :any]]
   [:response [:map-of :any :any]]])

(def RecipeLineage
  ;; Read-only view assembled from processing_runs + raw_scrapes for one recipe.
  ;; JSONB-sourced values arrive decoded; `techniques`/`outcome` are strings.
  ;; `raw.raw-content` is present but nil unless the request opts into the body.
  [:map
   [:recipe-url :string]
   [:recipe-id  :uuid]
   [:run  [:map
           [:id               :uuid]
           [:pipeline-version :string]
           [:outcome          :string]
           [:error-detail     [:maybe :map]]
           [:techniques       [:map
                               [:acquire   {:optional true} [:maybe :string]]
                               [:parse     {:optional true} [:maybe :string]]
                               [:normalize {:optional true} [:maybe :string]]]]
           [:facts            [:maybe ExtractedFacts]]
           [:content          [:maybe RecipeContent]]
           [:llm-calls        [:sequential LlmCall]]
           [:warnings         [:sequential :string]]
           [:created-at       some?]]]
   [:raw  [:map
           [:source-kind   :string]
           [:request-url   {:optional true} [:maybe :string]]
           [:final-url     {:optional true} [:maybe :string]]
           [:http-status   {:optional true} [:maybe :int]]
           [:fetch-tier    {:optional true} [:maybe :string]]
           [:content-bytes :int]
           [:raw-content   [:maybe :string]]
           [:created-at    some?]]]])
