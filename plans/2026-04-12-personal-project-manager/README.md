# Overview

I'd like to build a personal project management system into my app. The system should allow a few different user behaviors:
1. The user should be able to record a new idea by talking to the application or writing notes.
2. The user should be able to see all ideas that already exist - this should be in a list and in a graph form. In graph form, ideas might be clustered together into a hierarchy, similar to a mind map
3. After the user creates an idea, they should be able to develop the idea further (clarify intent), develop the skills to execute on the idea (identify skill gaps) or execute on the idea (execute).
4. Developing the idea further should be an interactive experience. This should be agentic in nature - the agent will act as a coach/expert in the ideas field and prompt the user to clarify their thoughts.
5. Identifying skill gaps should proceed by first identifying key atomic skills the user could practice/would need in executing the project. This should be displayed in a tree/path - there may be several ways to get to the end, similar to how games show skill progressions that unlock new skills.
6. Newly created ideas should be given a maturity score - indicating how ready they are to be executed on. This should be a combination of how well the architecture is understood, and how well the application's intent is understood.

## Scoring
The Architectural clarity should not be manually scored. The app will score architectural clarity on several dimensions:
1. Language choice
2. Framework/library choices
3. Module design
4. API design
5. Infrastructure design
6. Testing design
7. Monitoring/debugging design 


The Intent clarity should not be manually scored. The app will score Intent clarity on several dimensions:
1. How well are allowed user behaviors enumerated
2. How well are edge cases explained and accounted for
3. How complete is the description of the idea
4. Are there clear feature descriptions
5. Is the user persona well understood

While these two types of scores will be the scores we start with, the API design should be extensible and allow the user to define their own scores and criteria. This means the API must not hard-code the specific intent and architecture clarity scores, and both the intent and architecture clarity scores should be data-driven (and used as starting seeds). Scores should be defineable and editable - when defining a score the user would describe the overall score and then the criteria that go into the overall score in plain English text.

## API

The project response api should not directly embed the score as a key - instead it should return a vector/array of scores that are fully self-documenting. The scores should also be identifiable by their ID (a UUID) and version number. All versions of the scores should be saved, so that we can better audit the output.
```json
{
  ...
  "scores": [
	{"name": "Architecture",
	 "overall": 6,
	 "dimensions": [{"name": "moduleDesign", "value": 4}]
        },
	{"name": "Architecture",
        }
  ]
}

```

## Implementation details
Once submitted the system should automatically score the ideas on the different dimensions identified. In code, we should use an interface for scoring, so we can swap out implementations. The production implementation should use LLM agents for scoring.

The Product manager agent should be an expert product manager, who is trying to help evaluate the strength of the user's idea. The Product manager agent is responsible for scoring the Intent clarity.

The Engineering Lead agent should be an expert software Engineer, helping vet the design and extensibility of the proposal. The Engineering Lead agent is responsible for scoring the Architectural clarity.
