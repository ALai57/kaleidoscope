(ns andrewslai.cljs.pages.editor
  (:require [andrewslai.cljs.navbar :as nav]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [cljsjs.slate-react]
            [cljsjs.slate]))

(comment
  (.log js/console js/Slate)
  [:> js/SlateReact.Editor]
  (.log js/console (js/SlateReact.Editor.))

  ;;https://reactrocket.com/post/slatejs-basics/ 
  
  )

;; https://github.com/jhund/re-frame-and-reagent-and-slatejs/blob/master/src/cljs/rrs/ui/slatejs/views.cljs
  Uses a form3 reagent component to manage React lifecycle methods."
(defn editor []
  (let [section-data (subscribe [:editor-data])
        html (:html @section-data)
        this-atom (atom nil)
        ;; `cached-editor-values-atom` is a map with a key for every edited
        ;; section. The value under the section's key is the cached editor
        ;; value.
        cached-editor-values-atom slatejs.db/cached-editor-values-atom
        ;; `editor-ref-atom` stores a reference to the SlateJS editor. This is
        ;; required to implement the toolbar separately from the editor. We pass
        ;; the editor-ref to the toolbar so that it can cause a change to the
        ;; editor's value.
        editor-ref-atom slatejs.db/editor-ref-atom
        ;; The section's html. We use it to initialize the editor value when it
        ;; is edited for the first time. We use the cached editor value
        ;; thereafter.
        html (:html section-data)
        ;; Here we assign the initial editor value when the section is activated
        ;; for editing.
        initial-ed-val (slatejs.core/effective-editor-value section-key html)
        ;; We store the initial editor value in the `cached-editor-values-atom`
        ;; where it will get picked up as the `:value` prop for the editor.
        _ (swap! cached-editor-values-atom assoc section-key initial-ed-val)
        ;; Callback when the editor changes, typically because of an edit by the
        ;; user.
        on-change-fn (fn [change-or-editor]
                       (let [new-value (.-value change-or-editor)]
                         ;; Update the cached editor value for the current section
                         (swap! cached-editor-values-atom assoc section-key new-value)
                         ;; Force an instant update. We could use a reagent/atom
                         ;; for editor ref and let the change to it trigger a
                         ;; re-render via reagent. Resetting it will trigger a
                         ;; re-render because it is de-refed in the props to the
                         ;; editor component. However it would only refresh once
                         ;; per animation frame (that's when reagent triggers a
                         ;; re-render). This results in slightly sluggish
                         ;; appearance of typing, and worse, it can result in
                         ;; garbled output if you type faster than reagent
                         ;; re-renders. The cursor will appear to jump around.
                         ;; So instead we trigger a force-update for instant
                         ;; editor re-renders.
                         (some-> @this-atom reagent/force-update)
                         ;; Dispatch event to persist new editor value in db.
                         (re-frame/dispatch [:rrs.ui.slatejs/evt-editor-value-changed section-key new-value])))
        update-editor-ref-fn (fn [component]
                               (when component (reset! editor-ref-atom component)))]
        cached-editor-value (atom nil)

        on-change-fn
        (fn [change-or-editor]
          (let [new-value (.-value change-or-editor)]
            (reset! cached-editor-value new-value)
            (some-> @this-atom reagent/force-update)            
            (dispatch [:editor-value-changed new-value])))]

    (reagent/create-class
      {:display-name "slatejs-editor-inner"
      {:display-name "slatejs-editor"
       :component-did-mount (fn [this] (reset! this-atom this))
       :component-will-unmount (fn [] (reset! this-atom nil))
       ;; The render function. Normally we would repeat the arguments to the
       ;; outer function, however they are not used here since we don't rely on
       ;; reagent to re-render the editor, but on `reagent/force-update`
       ;; instead.
       :component-will-unmount (fn [this] (reset! this-atom nil))
       :reagent-render (fn [_]
                         [:> npm.slatejs/Editor
                          {:auto-focus   true
                           :class-name   "rrs-slatejs-editor"
                           :id           (str "rrs-slatejs-editor-instance-" section-key)
                           :on-change    on-change-fn
                           :on-key-down  slatejs.core/on-key-down
                           :ref          update-editor-ref-fn
                           :render-mark  slatejs.core/render-mark
                           ;; When the editor is force-updated it will get its new value
                           ;; from the deref'd `cached-editor-values-atom`.
                           :value        (get @cached-editor-values-atom section-key)}])})))
                         [:> js/SlateReact.Editor
                          {:auto-focus true
                           :class-name "slatejs-editor"
                           :id "slatejs-editor-instance-1"
                           :on-change on-change-fn
                           :render-mark render-mark
                           :value (or @cached-editor-value
                                      @section-data
                                      (blank-value))}])})))

(defn editor-ui []
  [:div
   [nav/primary-nav]
   [:br]
   [:h1 "Editor"]
   [:div
    [editor]]])
