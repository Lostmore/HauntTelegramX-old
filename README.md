# HauntTelegramX

Telegram bot that connects players and administrators with game servers. It
provides account linking, remote player actions, server monitoring, support
tickets and repository notifications through a single Telegram interface.

## Features

- Telegram command and callback routing
- Role-based access for administrators and support staff
- TCP communication with game servers using asynchronous request/response handling
- Player account linking and game commands from Telegram
- Server availability, statistics and operational notifications
- Administrative server actions with access checks
- Support ticket workflow
- GitHub and GitLab notifications
- MySQL persistence through HikariCP

## Server Communication

The bot acts as a gateway between Telegram and one or more game servers. A
Netty-based client performs health checks, requests runtime statistics and
receives server notifications without blocking Telegram update processing.
Player actions and account-linking requests are forwarded to the game server,
which remains responsible for validating and executing them. Administrative
operations are additionally restricted by the bot's role-based access policy.

## Requirements

- Java 21
- MySQL
- Telegram bot token

## Configuration

Use [`.env.example`](.env.example) as the environment template. Database and
runtime settings are loaded from an external `config.yml` placed next to the
application JAR.

## Build

```shell
./gradlew build
```