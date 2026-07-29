//+------------------------------------------------------------------+
//|  XauTickPublisher.mq5                                            |
//|  Envia cada tick de XAUUSDm para o servidor WebSocket na VPS.   |
//|                                                                  |
//|  ANTES DE USAR:                                                  |
//|  1. Abra MT5 → Ferramentas → Opções → Expert Advisors           |
//|  2. Habilite "Permitir solicitações Web para URLs listadas"      |
//|  3. Adicione: http://127.0.0.1:8080                             |
//|                                                                  |
//|  Arraste o EA para o gráfico de XAUUSDm e confirme que o        |
//|  smiley face no canto do gráfico está verde (EA rodando).       |
//+------------------------------------------------------------------+
#property copyright "GoldOverlay"
#property version   "1.00"
#property description "Publica ticks XAUUSDm → servidor WebSocket local"

input string InpServerURL  = "http://127.0.0.1:8765/tick"; // URL do servidor
input int    InpTimeoutMs  = 2000;                          // Timeout HTTP (ms)

//+------------------------------------------------------------------+
void OnTick()
{
    MqlTick tick;
    if (!SymbolInfoTick(_Symbol, tick))
        return;

    // Monta JSON com bid, ask, tempo em ms e símbolo
    string json = StringFormat(
        "{\"bid\":%.5f,\"ask\":%.5f,\"time\":%I64u,\"symbol\":\"%s\"}",
        tick.bid,
        tick.ask,
        (ulong)tick.time_msc,   // milissegundos desde epoch
        _Symbol
    );

    char   post[];
    char   result[];
    string resultHeaders;
    string headers = "Content-Type: application/json\r\n";

    int jsonLen = StringLen(json);
    ArrayResize(post, jsonLen);
    StringToCharArray(json, post, 0, jsonLen, CP_UTF8);

    int res = WebRequest("POST", InpServerURL, headers, InpTimeoutMs, post, result, resultHeaders);

    if (res == -1)
    {
        int err = GetLastError();
        // 4014 = URL não permitida (adicione nas opções do MT5)
        if (err == 4014)
            Print("ERRO: Adicione ", InpServerURL, " nas opções de WebRequest do MT5");
        else if (err != 0)
            Print("WebRequest erro: ", err);
    }
}

//+------------------------------------------------------------------+
int OnInit()
{
    Print("XauTickPublisher iniciado → ", InpServerURL);
    Print("Símbolo: ", _Symbol);
    return INIT_SUCCEEDED;
}

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
{
    Print("XauTickPublisher encerrado");
}
