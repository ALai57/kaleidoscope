(ns kaleidoscope.http-api.auth.access-control)

(def public-access
  [{:pattern #".*"
    :handler (constantly true)}])
