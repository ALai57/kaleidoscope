(ns kaleidoscope.workflows.protocol)

(defprotocol IWorkflowExecutor
  (execute-step! [this db project step-run output-stream]
    "Execute step-run against the appropriate agent. Streams token SSE events to
     output-stream and writes a final step_complete event. Updates the step_run
     row in the database (running → completed/failed). Returns the full output
     string.")

  (recommend-workflows [this project live-workflows]
    "Rank live-workflows against the project description.
     Returns [{:workflow-id :name :confidence :rationale}] sorted by confidence
     descending."))
