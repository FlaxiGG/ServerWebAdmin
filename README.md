# Join Discord server
https://discord.gg/EYaT5EkD7U

---
# MinePanel

A lightweight, secure, and modern self-hosted web management panel for Minecraft servers.

MinePanel allows server owners to monitor, manage, and administrate their Minecraft server directly from a web browser — without relying on heavyweight external panels or complex infrastructure.

Designed for server owners who want a fast, simple, and powerful alternative to traditional server management solutions.

---

# Core Features

MinePanel combines real-time monitoring, remote administration, security, and server management into a single embedded web panel.

---

## Web-Based Management Panel

Manage your Minecraft server entirely from your browser.

Features:

- Embedded web server powered by NanoHTTPD
- No external dependencies required
- Lightweight architecture
- Self-hosted deployment
- Accessible locally or remotely

No external control panel installation required.

---

## Real-Time Server Monitoring

Monitor server health in real time.

Available metrics:

- Online player count
- TPS monitoring
- RAM usage tracking
- Real CPU usage monitoring
- Server uptime tracking
- Operating system information

Built for quick server diagnostics.

---

## Player Management System

Manage online players remotely.

Supported actions:

- View online players
- Kick players
- Ban players
- Whitelist management
- Ban list management
- Player administration controls

No need to access the Minecraft console directly.

---

## Live Console Streaming

View server console logs directly from the browser.

Features:

- Real-time console streaming
- Automatic refresh system
- Browser-based console monitoring
- Console integrated directly into dashboard

Monitor your server without SSH access.

---

## Authentication & Security

MinePanel was designed with security as a priority.

Implemented security systems:

- Username/password authentication
- BCrypt password hashing
- UUID v4 session tokens
- HttpOnly secure cookies
- Session inactivity timeout
- Automatic logout system
- Forced password change on first login

Secure by default.

---

## Multi-User Management System

Support multiple administrator accounts.

Available functions:

- Create multiple users
- Delete users
- Password management
- Secure password storage
- User isolation system

Designed for multi-staff server management.

---

## Role-Based Access Control (RBAC)

Fine-grained permission system for server staff.

Available roles:

- Owner
- Admin
- Moderator
- Viewer

Permission architecture includes:

- Frontend UI restrictions
- Page access protection
- Backend API permission validation

Three-layer security architecture prevents unauthorized access.

---

## Built-in File Manager

Manage server files directly from the browser.

Available functions:

- Browse files and folders
- Read text files
- Edit configuration files
- Save file changes
- Upload files
- Download files
- Rename files and folders
- Delete files and folders
- Create folders

Supported file management permissions:

- files.view
- files.read
- files.edit
- files.upload
- files.download
- files.delete

No FTP required.

---

## Network Configuration System

Flexible network deployment options.

Supported configurations:

- Custom web server host
- Custom web server port
- Reverse proxy support
- X-Forwarded-For detection
- Localhost-only deployment mode
- External access control
- Public IP compatibility improvements
- Safe bind fallback system

Compatible with multiple hosting environments.

---

## Modern User Interface

Designed for usability.

Features:

- Responsive dashboard layout
- Light/Dark theme support
- Fixed sidebar navigation
- Auto-refresh pages
- Mobile-friendly design
- Custom confirmation modals
- Permission-aware UI rendering

Clean and minimal interface.

---

# Supported Versions

Currently tested and supported on:

- 1.13.2
- 1.14.x
- 1.15.x
- 1.16.x
- 1.17.x
- 1.18.x
- 1.19.x
- 1.20.x
- 1.21.x
- 26.x

Java compatibility:

- Java 8+
- Tested on Java 8
- Tested on Java 21
- Tested on Java 25

Supported server software:

- Spigot
- Paper
- Purpur (Experimental)

Legacy support may be considered in future releases.

---

# Installation

Download the latest release.

Place the plugin inside:

```text
/plugins/
```

Start your Minecraft server.

MinePanel will automatically generate required configuration files.

Open your browser:

```text
http://localhost:8080
```

Default port can be changed inside configuration.

---

# Default Login

Default credentials generated automatically on first startup.

```text
Username: owner
Password: admin123
```

Security policy:

- Password change is mandatory on first login

Please change the default password immediately.

---

# Configuration Example

Example `config.yml`

```yaml
web:
  host: 0.0.0.0
  port: 8080
  allow-external: true

security:
  session-timeout-minutes: 5
```

---

# Reverse Proxy Support

MinePanel supports reverse proxy deployments for additional security.

Example using Nginx:

```nginx
server {
    listen 80;
    server_name panel.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Recommended architecture:

```text
MinePanel → 127.0.0.1:8080 → Nginx → Domain
```

---

# Commands

Available commands:

```text
/webadmin reload
/webadmin players
/webadmin kick
/webadmin ban
```

Reload command safely restarts the embedded web server and reloads configuration.

---

# Security Architecture

MinePanel implements multiple security layers.

Implemented protections:

- BCrypt password hashing
- HttpOnly secure cookies
- Session expiration
- Automatic session cleanup
- Secure logout handling
- Reverse proxy compatibility
- Localhost-only deployment mode
- Permission-based API protection
- Path traversal protection
- Dangerous file type blocking
- Protected server core files

Security-first design philosophy.

---

# Screenshots

<img width="1919" height="991" alt="image" src="https://github.com/user-attachments/assets/b5143182-0a72-469f-8f05-51c7d0c4a522" />
<img width="1919" height="993" alt="image" src="https://github.com/user-attachments/assets/7dc85fe6-7338-4f57-bdce-aa486bf62ef8" />

---

# Project Philosophy

MinePanel was created with one simple idea.

Provide Minecraft server owners with a lightweight, secure, modern, and self-hosted management panel without depending on large external hosting panels.

Core philosophy:

```text
Simple

Fast

Secure

Reliable
```

Build only what server owners actually need.

---

# Contributing

Community feedback is extremely valuable.

You can help improve MinePanel by:

- Reporting bugs
- Suggesting new features
- Providing testing feedback
- Opening GitHub issues

All feedback is welcome.

---

# License

MinePanel is licensed under GNU GPL v3.

You are free to:

- Use the plugin for personal servers
- Use the plugin for commercial servers
- Modify the source code
- Fork the project
- Redistribute modified versions

Requirements:

- Modified versions must remain open source
- Modified versions must also use GPL v3 license
- Source code must remain publicly available when redistributed

Please respect the project and support original development.

---

# Future Development

MinePanel is actively developed based on real community feedback.

Upcoming improvements may include:

- Advanced file management
- Backup systems
- Better permission granularity
- Additional server administration tools

More features coming soon.

---

Built with ❤️ by FlaxiLabs
