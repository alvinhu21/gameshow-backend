# gameshow-backend

A high-performance Java-based service designed to orchestrate the core logic for digital versions of "Wheel of Fortune" and "Jeopardy!" This application manages game states, category management, and scoring systems through a robust Spring Boot framework and a localized SQLite3 database.

## Core Functionality

* **Game Orchestration:** Handles the distinct logic for both "Wheel of Fortune" (spin results, vowel purchases, phrase solving) and "Jeopardy!" (daily doubles, clue values, and response validation).
* **Data Persistence:** Utilizes SQLite3 for a lightweight yet powerful data layer, storing question banks, user statistics, and active session states.
* **RESTful API Services:** Provides endpoints for frontend consumers to fetch clues, update player balances, and sync game progress in real-time.
* **Business Logic Layer:** Implements rules-based validation to ensure game integrity across concurrent sessions.

## Future Expansion
The system architecture is explicitly designed for modular growth. While currently focused on "Wheel of Fortune" and "Jeopardy!", the service layer is built to integrate additional game show formats (e.g., "Family Feud" or "Who Wants to Be a Millionaire?") without requiring a total schema redesign.

## Technical Stack

* **Backend:** Java 17+, Spring Boot (Web, Data JPA)
* **Database:** SQLite3
* **Build Tool:** Maven
* **Version Control:** Git

## Getting Started

### Prerequisites
* Java Development Kit (JDK) 17 or higher
* Maven 3.8+
* SQLite3 installed locally

### Installation and Setup

1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/alvinhu21/gameshow-backend.git](https://github.com/alvinhu21/gameshow-backend.git)
    ```
2.  **Navigate to the directory:**
    ```bash
    cd gameshow-backend
    ```
3.  **Configure Database:**
    The application is pre-configured to initialize `wheelOfFortune.db` and `jeopardy.db` files in the root directory upon startup. Ensure the application has write permissions for this folder.
4.  **Build and Run:**
    ```bash
    mvn clean install
    mvn spring-boot:run
    ```

## Project Standards
This project follows a modular architecture, separating the web controllers from the service layer to ensure high maintainability. Every feature is developed with scalability in mind, allowing for the future addition of more game show formats without restructuring the core database schema.

---
© 2026 Alvin Hu
