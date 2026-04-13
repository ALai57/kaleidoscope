A user should be able to create a number of unique workflows that describe how agents interact once a project is created.

For example, if I submit a "Cooking" project, the workflow might be "Research recipes", then "Ask me about ingredients I already have", then "Present suggestions and research" then "Save recipe in recipe database",

whereas, if the project is a "Research" project, the workflow might be "Break down project into atomic steps", "Identify missing skills", "Estimate cost/time to build skills and investment for learning".

All of these workflows should be configurable, flexible, and separately defineable in the app. The app would take any incoming Project and classify it into a specific workflow based on the text in the description. Then it would suggest a specific workflow from the selection of available workflows and ask the user if it should use that workflow.

How a user would use this

User defines a workflow and the relevant steps in the workflow in English.

Once the workflow is saved and marked as "live", it's entered into a registry/pool of all available workflows. When the user creates a new Project, the app recommends a specific workflow. That workflow is displayed on the project's subpage (e.g. http://andrewslai.com.localhost:5173/projects?project=b59ca316-b5d5-4c56-9b92-4d6e0fe96b78). The subpage then lets the user go through the workflow step-by-step in the app with user intervention, or allows the app to autonomously execute the workflow.

The app should come pre-seeded with a workflow: Feature development. This workflow is

Evaluate product idea

Evaluate Engineering architecture. if architecture score is below a 5, ask the Engineering Architect to suggest ways to implement the feature and, once those recommendations are added to the document, re-score.

## User flows

A user should be able to skip a workflow step if they don't want to go through it. They should be able to directly ask a specific agent to take a new action. At the end of that action, the app should try to use the output of the last action to classify it and see if any workflows fit. It should then recommend the most relevant workflow.

The step the user overrode with should be recorded as a "custom step", and there should be a record that the custom step was inserted into the workflow.
