# ChangeScope

ChangeScope is a GitHub-integrated code change impact analysis platform for Java and Spring Boot repositories. It analyzes pull requests and code changes, constructs dependency graphs, identifies potentially affected classes and test suites, explains the ripple effects of modifications, and surfaces actionable insights in an interactive dashboard.

> **Status:** Project foundation phase. Implementation has not started yet.

---

## Planned Technology Stack

- **Backend:** Java 21, Spring Boot 3.x, Apache Maven
- **Frontend:** React, TypeScript, Vite, Tailwind CSS, shadcn/ui
- **Integration:** GitHub REST / GraphQL APIs, Git diff parser, Java AST / static analysis
- **Documentation:** Markdown documentation and architecture specs in `docs/`

---

## Repository Structure (Planned)

```
ChangeScope/
├── backend/       # Spring Boot impact analysis service and GitHub integration
├── frontend/      # React + Vite interactive dashboard
├── docs/          # Architecture design, API specs, and technical documentation
├── .gitignore     # Root ignore rules for Java, Node, IDEs, and secrets
└── README.md      # Project overview and documentation
```
