(ns andrewslai.cljs.pages.editor
  (:require [ajax.core :refer [POST]]
            [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.events.editor :as ed]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [cljsjs.slate-react]
            [cljsjs.slate]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Renderers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn bold [attributes children]
  [:strong attributes (clj->js children)])

(defn italic [attributes children]
  [:em attributes (clj->js children)])

(defn code [attributes children]
  [:pre attributes [:code (clj->js children)]])

(defn code-block [attributes children]
  [:pre.code-block attributes [:code (clj->js children)]])

(defn paragraph [attributes children]
  [:p attributes (clj->js children)])

(defn get-renderer [obj]
  (get {"bold"       bold
        "italic"     italic
        "code"       code
        "code-block" code-block}
       (:text-type obj)
       paragraph))

(defn get-type [text-obj]
  (.-type text-obj))

(defn props->clj
  [props]
  (let [{:keys [node mark] :as p} (js->clj props :keywordize-keys true)]
    (assoc p :text-type (get-type (or node mark)))))

;; https://github.com/jhund/re-frame-and-reagent-and-slatejs/blob/master/src/cljs/rrs/ui/slatejs/views.cljs
;; https://reactrocket.com/post/slatejs-basics/
(defn render
  "Renders a slatejs mark to HTML."
  [props editor next]
  ;;(js/console.log "PROPS!!" (js->clj props :keywordize-keys true))
  (let [{:keys [attributes children] :as properties} (props->clj props)
        renderer (get-renderer properties)]
    (reagent/as-element (renderer attributes children))))

(defn toggle-mark
  "Toggles `mark-type` in editor's current selection."
  [event mark-type change editor]
  (let [props (.-props editor)
        on-change (.-onChange props)
        updated-editor (.toggleMark change mark-type)]
    (.preventDefault event)
    (on-change updated-editor)))

(defn insert-string
  [s event change editor]
  (let [props (.-props editor)
        on-change (.-onChange props)
        updated-editor (.insertText change s)]
    (.preventDefault event)
    (on-change updated-editor)))

(defn ctrl-b? [event]
  (and event.ctrlKey (= event.keyCode 66)))

(defn ctrl-i? [event]
  (and event.ctrlKey (= event.keyCode 73)))

(defn ctrl-enter? [event]
  (and event.ctrlKey (= event.keyCode 13)))

(defn tab? [event]
  (= event.keyCode 9))

;; https://www.strilliant.com/2018/07/15/let%E2%80%99s-build-a-fast-slick-and-customizable-rich-text-editor-with-slate-js-and-react/ 
;; https://stackoverflow.com/questions/56081674/enter-and-backspace-not-working-with-slate-js-editor-in-react 
(defn key-down-handler
  "Event callback for keyDown event."
  [event change editor]
  ;; (.log js/console "Event " event.ctrlKey event.keyCode)
  ;; (.log js/console "Change " change)
  ;; (.log js/console "Editor" editor)
  (cond
    (ctrl-b? event) (toggle-mark event "bold" change editor)
    (ctrl-i? event) (toggle-mark event "italic" change editor)
    (ctrl-enter? event) (insert-string "\n" event change editor)
    (tab? event) (insert-string "\t" event change editor)))

(defn blank-value
  "Returns a blank editor value."
  []
  (->> {:object "value"
        :document {:object "document"
                   :nodes [{:object "block"
                            :type "paragraph"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Hello world! "}
                                              {:object "leaf"
                                               :text "Bold "
                                               :marks [{:type "bold"}]}
                                              {:object "leaf"
                                               :text "Italics "
                                               :marks [{:type "italic"}]}]}]}
                           {:object "block"
                            :type "code-inline"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Code inline"}]}]}
                           {:object "block"
                            :type "code-block"
                            :nodes [{:object "text"
                                     :leaves [{:object "leaf"
                                               :text "Code block\n Hello"}]}]}]}}
       clj->js
       (.fromJSON js/Slate.Value)))


(defonce editor-ref-atom (atom nil))
(defn editor []
  (let [section-data (subscribe [:editor-data])
        html (:html @section-data)

        this-editor (atom nil)
        editor-text (atom nil)

        update-editor-ref
        (fn [component]
          (when component (reset! editor-ref-atom component)))

        change-handler
        (fn [change-or-editor]
          (let [new-value (.-value change-or-editor)]
            (reset! editor-text new-value)
            (some-> @this-editor reagent/force-update)
            (dispatch [:editor-text-changed new-value])))]

    (reagent/create-class
     {:display-name "slatejs-editor"
      :component-did-mount (fn [this] (reset! this-editor this))
      :component-will-unmount (fn [this] (reset! this-editor nil))
      :reagent-render (fn [_]
                        [:> js/SlateReact.Editor
                         {:auto-focus true
                          :class-name "slatejs-text-editor"
                          :id "slatejs-editor-instance-1"
                          :on-change change-handler
                          :on-key-down key-down-handler
                          :render-mark render
                          :render-node render
                          :ref update-editor-ref
                          :value (or @editor-text
                                     @section-data
                                     (blank-value))}])})))

(defn serialized-data []
  (let [section-data (subscribe [:editor-data])]
    (when @section-data

      [:div {:type "content"
             :id "editor-article-content"}
       (str "<div>" (:html (ed/editor-model->clj @section-data)) "</div>")])))

(defn form-data->map [form-id]
  (let [m (atom {})]
    (-> js/FormData
        (new (.getElementById js/document form-id))
        (.forEach (fn [v k obj] (swap! m conj {(keyword k) v}))))
    (swap! m assoc
           :content (->> "editor-article-content"
                         (.getElementById js/document)
                         (.-innerText)))))

(defn editor-ui []
  (let [{:keys [username firstName lastName] :as user} @(subscribe [:update-user-profile!])
        {:keys [article_tags title]} @(subscribe [:editor-metadata])]
    [:div
     [nav/primary-nav]
     [:br]
     [:h1 "Editor"]
     [:br]
     [:form {:id "editor-article-form" :class "slatejs-article-editor"}
      [:input.editor-title {:type "text"
                            :placeholder "Article title"
                            :name "title"
                            :on-change (fn [x]
                                         (dispatch [:editor-metadata-changed
                                                    (form-data->map "editor-article-form")]))}]
      [:br]
      [:input.editor-author {:type "Author"
                             :placeholder "Author"
                             :name "author"
                             :readOnly true
                             :value (when user (str firstName " " lastName))}]
      [:br]
      [:select {:id "article-tags-input"
                :type "Article tags"
                :name "article_tags"
                :style {:display "none"}
                :on-change
                (fn [x]
                  (dispatch [:editor-metadata-changed
                             (form-data->map "editor-article-form")]))}
       [:option {:value "thoughts"} "Thoughts"]
       [:option {:value "research"} "Research"]
       [:option {:value "data-analysis"} "Data Analysis"]]
      [:br]
      [:br]
      [:label.url {:id "article-url-label"} (str "https://andrewslai.com/#/"
                                                 (or article_tags "thoughts")
                                                 "/content/ ")]
      [:br]
      [:br][:br]
      [:h5 "How text looks in an article"]
      [:div {:style {:border-style "double"}}
       [editor]]]
     [:br] [:br]
     [:div.serialized-article {:id "editor-article-form"
                               :class "slatejs-article-editor"}
      [:h5 "How text looks in the database"]
      [serialized-data]]
     [:input {:type "button"
              :on-click (fn [x]
                          (dispatch [:save-article! (form-data->map "editor-article-form")]))
              :value "Save article!"}]]))
