---
name: feedback-deployment-commands
description: Always use Taskfile tasks for deployments, not raw fly/shell commands
metadata:
  type: feedback
---

Always deploy via `task <name>` rather than running `fly deploy` or other deployment commands directly. If no task exists for the operation, propose adding one first rather than running the command bare.

**Why:** User wants all deployment operations to go through Taskfile tasks so they're documented, repeatable, and consistent.

**How to apply:** Before running any deployment command (fly deploy, docker push, etc.), check if a task exists. If not, add one to Taskfile.yml and propose it. Only run deployment operations via `task <name>`.
