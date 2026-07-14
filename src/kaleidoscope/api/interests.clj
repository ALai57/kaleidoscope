(ns kaleidoscope.api.interests
  (:require [clojure.string :as str]
            [kaleidoscope.persistence.interests :as persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]))

(def default-taste-profile
  {:keywords        []
   :formats         []
   :lengths         []
   :trusted-sources []
   :novelty-ratio   0.5
   :cadence         "weekly"
   :refinements     []})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interest CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-interests
  [db user-id]
  (persistence/get-interests db user-id))

(defn get-interest
  "Return a single interest. Ownership is enforced by the persistence layer's
  WHERE clause, not a check here."
  [db user-id interest-id]
  (persistence/get-interest db interest-id user-id))

(defn create-interest!
  [db user-id {:keys [intent taste-profile]}]
  (persistence/create-interest! db {:user-id       user-id
                                    :intent        intent
                                    :taste-profile (merge default-taste-profile taste-profile)}))

(defn update-interest!
  "Update intent and/or taste profile. Taste-profile edits merge over the
  stored profile so a partial edit (e.g. just the novelty dial) never wipes
  the rest. Returns nil if not found or not owned."
  [db user-id interest-id {:keys [intent taste-profile]}]
  (when-let [interest (persistence/get-interest db interest-id user-id)]
    (persistence/update-interest! db interest-id user-id
                                  (cond-> {}
                                    intent                (assoc :intent intent)
                                    (some? taste-profile) (assoc :taste-profile
                                                                 (merge (:taste-profile interest)
                                                                        taste-profile))))))

(defn delete-interest!
  [db user-id interest-id]
  (persistence/delete-interest! db interest-id user-id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shelf (recommendations) — always gated by interest ownership
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-shelf
  "Return the interest's recommendations (optionally filtered by status/kind).
  Returns nil — not [] — when the interest isn't owned, so the HTTP layer can
  404 instead of leaking an empty-but-real shelf."
  [db user-id interest-id filters]
  (when (persistence/get-interest db interest-id user-id)
    (recommendations-persistence/get-recommendations db interest-id filters)))

(defn update-recommendation-status!
  [db user-id interest-id recommendation-id status]
  (when (persistence/get-interest db interest-id user-id)
    (recommendations-persistence/update-recommendation-status!
     db recommendation-id interest-id status)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Refinement folding (clarify answers + check-ins)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fold-refinement
  "Fold clarify/check-in answers into a taste profile: answers append to
  :refinements. The intent stays user-owned and untouched."
  [taste-profile answers]
  (update taste-profile :refinements
          (fnil into []) (remove str/blank? answers)))

(defn fold-refinement!
  [db user-id interest-id answers]
  (when-let [interest (persistence/get-interest db interest-id user-id)]
    (persistence/update-interest! db interest-id user-id
                                  {:taste-profile (fold-refinement (:taste-profile interest)
                                                                   answers)})))
