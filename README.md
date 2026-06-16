# MinePanel

A lightweight and modern **web-based management panel for Minecraft servers**, designed to provide real-time server monitoring, player management, authentication, and remote administration directly from your browser.

Built for server owners who want a simple but powerful alternative to traditional server management methods.

---

## Features

### Web-Based Management Panel

* Browser-based dashboard
* No external dependencies required
* Embedded web server powered by NanoHTTPD

### Real-Time Monitoring

* Online player counter
* TPS monitoring
* RAM usage monitor
* Real CPU usage monitoring
* Server uptime tracking
* Operating system information

### Player Management

* View online players
* Kick players
* Ban players
* Whitelist management
* Ban management

### Live Console Streaming

* Real-time server console log viewer
* Automatic refresh system
* Console integrated directly into dashboard

### Authentication & Security

* Username/password authentication
* BCrypt password hashing
* UUID v4 session tokens
* HttpOnly cookie authentication
* Session inactivity timeout
* Automatic logout system
* Forced password change for default admin account

### User Management

* Multiple admin users
* Add/remove users
* Password management
* Secure password storage

### Network Configuration

* Configurable web server host
* Configurable web server port
* Reverse proxy support
* X-Forwarded-For detection
* Localhost-only deployment option
* External access control (`allow-external`)

### User Interface

* Modern responsive dashboard
* Light/Dark theme support
* Fixed sidebar navigation
* Custom confirmation modals
* Auto-refresh pages
* Mobile-friendly layout

---

## Supported Versions

Currently tested and supported on:

* 1.13.2
* 1.14.x
* 1.15.x
* 1.16.x
* 1.17.x
* 1.18.x
* 1.19.x
* 1.20.x
* 1.21.x

Legacy versions may be supported in future updates.

---

## Installation

1. Download the latest release.

2. Place the plugin inside:

```text
/plugins/
```

3. Start your Minecraft server.

4. The plugin will automatically generate configuration files.

5. Open:

```text
http://localhost:8080
```

(Default port can be changed in config)

---

## Default Login

Default credentials created automatically on first startup:

```text
Username: admin
Password: admin123
```

You will be forced to change the default password after first login.

---

## Configuration

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

## Reverse Proxy Example

MinePanel supports reverse proxy deployments for improved security.

Example with Nginx:

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

Recommended secure setup:

```text
MinePanel → 127.0.0.1:8080 → Nginx → Domain
```

---

## Commands

```text
/webadmin reload
/webadmin players
/webadmin kick
/webadmin ban
```

Reload plugin configuration and restart embedded web server safely.

---

## Security Features

MinePanel was designed with security in mind.

Implemented protections:

* BCrypt password hashing
* HttpOnly session cookies
* Session expiration
* Automatic session cleanup
* Secure logout handling
* Reverse proxy compatibility
* Localhost-only deployment mode

---

## Roadmap

Planned future improvements:

* Better reverse proxy documentation
* Login rate limiting
* Security audit logs
* Performance optimizations
* WebSocket console streaming improvements

Future (long-term):

* BungeeCord support
* Multi-server network support
* Premium edition
* Advanced analytics dashboard

---

## Screenshots

<img width="1919" height="993" alt="image" src="https://github.com/user-attachments/assets/07167328-a1a4-4ac8-a968-d93280d5b3b5" />


---

## Project Philosophy

MinePanel was created with one goal:

Provide Minecraft server owners with a lightweight, secure, and modern web management panel without relying on large external panels.

Simple.

Fast.

Secure.

Reliable.

---

## Contributing

Feedback, bug reports, and suggestions are always welcome.

Please open an issue or submit feedback.

---

## Support

If you enjoy the project and want to support future development:

Patreon available for supporters.

[(Support Me)](https://patreon.com/FlaxiGG?utm_medium=unknown&utm_source=join_link&utm_campaign=creatorshare_creator&utm_content=copyLink)

---

## License

MinePanel is licensed under **GNU GPL v3**.

You are free to:

* Use the plugin for personal or commercial servers
* Modify the source code
* Fork the project
* Redistribute modified versions

Requirements:

* Modified versions must remain open source
* Modified versions must also use GPL v3 license
* Source code must remain publicly available when redistributed

Please respect the project and support original development.


---

Built with ❤️ by FlaxiLabs
