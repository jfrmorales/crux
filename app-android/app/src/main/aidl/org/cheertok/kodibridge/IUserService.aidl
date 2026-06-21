package org.cheertok.kodibridge;

interface IUserService {
    // Shizuku llama a destroy() al desvincular; el id de transaccion es fijo.
    void destroy() = 16777114;
    void exit() = 1;

    // Arranca el puente con la configuracion (JSON: host, port, user, pass, step...)
    void startBridge(String config) = 2;
    void stopBridge() = 3;
    String getStatus() = 4;
}
