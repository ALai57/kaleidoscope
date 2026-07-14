(ns kaleidoscope.timeline.protocol)

(defprotocol ITimelineGenerator
  (segment [this recipe changed-ids cached]
    "Segment a recipe's components into phases.

     recipe:      {:content <RecipeContent>}
     changed-ids: #{component-id …} — components whose steps changed
     cached:      stored [{:name :phases …}] for dependency reference (may be nil)

     Returns {:components [{:name    component-id
                            :phases  [{:id     \"{component-id}/{label}\"
                                       :label  str    ;; unique within component
                                       :kind   \"active\"|\"passive\"
                                       :steps  [int]   ;; indices into the component's steps
                                       :estimate int   ;; minutes
                                       :deps   [phase-id …]}]}]}
     Must include changed components; may include all."))
