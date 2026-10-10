// Web dev server (./gradlew :composeApp:wasmJsBrowserDevelopmentRun).
// Port 8081: 8080 is Kong in `docker compose up`, and LIBRARYZ_PUBLIC_URL
// (where emailed reset / verification links point) is http://localhost:8081.
// historyApiFallback serves index.html for /reset-password and
// /verify-email, so those links open the app.
if (config.devServer) {
    config.devServer.port = 8081;
    config.devServer.historyApiFallback = true;
}
