(ns kaleidoscope.models.registry
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [malli.experimental.time :as met]))

(mr/set-default-registry!
 (mr/composite-registry
  (m/default-schemas)
  (met/schemas)))
