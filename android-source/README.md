# J.A.R.V.I.S. Android

Aplicativo Android nativo em Kotlin. O projeto anterior era somente um visualizador remoto de tela; esta versão inicia a transformação em um cliente móvel do J.A.R.V.I.S.

## Recursos desta primeira base
- Interface de chat escura com detalhes ciano.
- Configuração de endereço HTTPS e autenticação pelo PIN local do Viewer.
- Sessão autenticada com token temporário.
- Lista/seleção de conversas e criação de nova conversa.
- Carregamento do histórico.
- Envio de mensagens ao Core do J.A.R.V.I.S. e recebimento da resposta.
- Sem chamadas a APIs pagas.

## Arquitetura de conexão
- Android usa HTTPS para o endereço privado do computador.
- O Tailscale Serve existente encaminha o acesso privado para 127.0.0.1:8765.
- viewer/server.py encaminha /api/* somente pelo loopback para a API local do Electron em 127.0.0.1:8766.
- desktop/mobile_api.js valida o PIN, emite tokens temporários e chama o worker/Core existente.
- A API Node escuta somente em 127.0.0.1; não abre porta pública.
- O worker serializa seleção de conversa e geração de chat entre o Desktop e o cliente móvel.
- O fluxo móvel não dispara TTS no computador.

## Requisitos para conexão remota
1. O PC precisa estar ligado, com o J.A.R.V.I.S. Desktop aberto.
2. O Tailscale precisa estar ativo no PC e no Android, ambos na mesma tailnet.
3. O Tailscale Serve deve encaminhar HTTPS para o Viewer local na porta 8765.
4. No aplicativo, informar o endereço HTTPS do computador e o PIN de seis dígitos configurado pelo Viewer.
5. Não habilitar Tailscale Funnel nem expor diretamente as portas 8765/8766 à internet.

## Compilar
Requer JDK 17 ou superior e Android SDK com plataforma 36.

No PowerShell:
```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat assembleDebug
```

APK de debug:
app/build/outputs/apk/debug/app-debug.apk

## Limitações atuais
- Primeira versão de desenvolvimento; ainda precisa de teste real em um aparelho Android.
- A geração retorna a resposta completa; streaming visual de tokens será adicionado depois.
- O Android depende do PC ligado para usar o Qwen3 8B e o Core completo.
- Voz, controle remoto do PC, notificações e modo offline ainda não estão implementados.
- A segurança da conexão remota depende da configuração correta do Tailscale Serve e da tailnet privada.

## Baixar e instalar no celular
1. Conecte o Android ao mesmo Tailscale privado do PC.
2. Abra o endereço HTTPS do PC seguido de `/download`.
3. Informe o PIN de seis dígitos do Viewer para baixar o APK.
4. Abra o arquivo baixado e permita a instalação pelo navegador, se o Android solicitar.
5. Abra o J.A.R.V.I.S.; o endereço do PC já vem preenchido. Informe novamente o PIN para autenticar o aplicativo.

O download é servido apenas pela tailnet privada e exige o PIN. Não compartilhe o APK nem o PIN publicamente.


Link privado de download validado: https://arthur.tail25244c.ts.net/download
O endereço só funciona com o Tailscale conectado à mesma tailnet do computador e exige o PIN do Viewer.
