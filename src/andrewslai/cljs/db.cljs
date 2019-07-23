(ns andrewslai.cljs.db
  (:require [cljs.reader]
            [cljs.spec.alpha :as s]
            [re-frame.core :as re-frame]))


;; -- Spec --------------------------------------------------------------------
;;
;; The value in app-db should always match this spec. Only event handlers
;; can change the value in app-db so, after each event handler
;; has run, we re-check app-db for correctness (compliance with the Schema).
;;
;; How is this done? Look in events.cljs and you'll notice that all handlers
;; have an "after" interceptor which does the spec re-check.

(s/def ::active-panel keyword?)
;;(s/def ::title string?)
(s/def ::loading boolean?)

(s/def ::db
  (s/keys :rEeq-un [::active-panel
                    ::active-content
                    ::loading?]))

;; -- Default app-db Value  ---------------------------------------------------

(def default-db           ;; what gets put into app-db by default.
  {:active-panel :home
   :active-content nil
   :recent-content nil
   :loading? false})        ;; show all todos
