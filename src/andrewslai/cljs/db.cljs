(ns andrewslai.cljs.db
  (:require [cljs.reader]
            [cljs.spec.alpha :as s]
            [re-frame.core :as re-frame]))


;; -- Spec --------------------------------------------------------------------
;; The value in app-db should always match this spec.

(s/def ::active-panel keyword?)
(s/def ::loading boolean?)
(s/def ::db
  (s/keys :rEeq-un [::active-panel
                    ::active-content
                    ::loading?]))

;; -- Default app-db Value  ---------------------------------------------------

(def default-db
  {:active-panel :home
   :active-content nil
   :recent-content nil
   :loading? false})
