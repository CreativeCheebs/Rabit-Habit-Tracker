# Run Habit Lab with Docker

The container runs the Kotlin sync server and its bundled web app. The Android app remains a separately installed APK. The Dockerfile builds the server from the standalone `sync-server/` Gradle project, then copies the application distribution into a smaller Java runtime image.

## Build and run locally

Install Docker Desktop or Docker Engine with Compose, and start it. In the repository root, create a file called `.env` containing a strong secret of at least 32 bytes. For example, generate a random value with `openssl rand -hex 32`, and put the result after the equals sign:

```dotenv
HABITLAB_SYNC_SECRET=replace-with-your-own-random-secret-of-at-least-32-bytes
HABITLAB_SYNC_ALLOW_SIGNUP=true
```

Keep the same secret across container restarts so existing bearer tokens remain valid. The `.env` file is ignored by Git; do not put it in an image or publish it. `HABITLAB_SYNC_ALLOW_SIGNUP=false` disables new account registration.

On Windows, after creating `.env` once, rebuild and restart with:

```powershell
.\scripts\rebuild-container.ps1
```

The script reuses the root `.env` and the existing named data volume. It will not create or replace the secret. It checks the local health endpoint after starting. Existing integration-token hashes stay in the data volume; the original token value is shown only when created and cannot be recovered from the server. The script changes the running local container; it does not publish an image.

From the repository root:

```sh
docker compose up --build -d
docker compose logs -f habitlab
```

Open `http://127.0.0.1:8787/` on the host; `http://127.0.0.1:8787/health` checks the service. The container listens on port 8787 internally, while Compose publishes it only on the host's loopback interface. For access from another device, configure an HTTPS reverse proxy on the host; use its HTTPS address in the app. Do not expose the plain HTTP listener to the internet.

The named volume `habitlab-data` stores `accounts.sqlite` and per-account databases in `/data`, surviving container replacement. Back it up before changes, following SQLite's safe backup guidance; deleting the volume deletes the data. To stop the container while keeping the volume, run `docker compose down`.

## Build a portable image

The Dockerfile is the build recipe. From the repository root:

```sh
docker build -t habitlab-sync:local ./sync-server
```

The image contains the web app and server, not runtime secrets or the database. The server requires `HABITLAB_SYNC_SECRET`, binds to `0.0.0.0:8787` inside the container, and stores data under `/data`. The bundled Compose file supplies the host port, persistent volume, and secret from your `.env` file.

## Publish to Docker Hub

Create a repository in Docker Hub, sign in locally with `docker login`, and replace `<dockerhub-user>` and `<version>` with your account and desired release version:

```sh
docker build -t <dockerhub-user>/habitlab-sync:<version> ./sync-server
docker push <dockerhub-user>/habitlab-sync:<version>
```

Others can pull that exact image and run it with the same required environment variable and a persistent `/data` volume. Publish only after the local build and startup checks succeed. For automated releases, use GitHub Actions with a Docker Hub access token stored as a GitHub secret; Docker Hub's older Automated Builds feature is being retired.
