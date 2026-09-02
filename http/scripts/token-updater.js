export function runTokenUpdater(command) {
    const environmentName = request.environment.get("environmentName");
    if (environmentName !== "local" && environmentName !== "prod") {
        throw new Error("Run with에서 local 또는 prod 환경을 선택하세요.");
    }

    const tokenUpdaterPath = request.environment.get("tokenUpdaterPath");
    if (!tokenUpdaterPath || tokenUpdaterPath.startsWith("replace-")) {
        throw new Error("tokenUpdaterPath에 update-access-token.sh의 절대 경로를 입력하세요.");
    }

    return String(execFileSync(
        "bash",
        [tokenUpdaterPath, environmentName, command]
    ) ?? "").trim();
}
