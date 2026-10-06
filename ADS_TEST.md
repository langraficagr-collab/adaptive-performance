# Versão com anúncios — 1.4.9

A correção do congelamento rejeita respostas com exceções ou erros do Android e mostra o motivo da última tentativa no painel.

## Instalação separada para teste

Compile com `gradle :app:assembleDebug -PsideBySide=true -PproductionAds=false`.

O pacote `com.mauricio.adaptiveperformance.adstest`, identificado como **Adaptive Performance Ads Test**, usa anúncios de demonstração do Google e pode coexistir com a versão sem anúncios. Os dados e o processo Shizuku são independentes. Evite ativar os dois otimizadores simultaneamente.

A compilação padrão mantém o pacote original e os IDs AdMob de produção; instalá-la sobre a versão sem anúncios substitui essa edição.

Se o freezer do Android estava desativado, é necessário reiniciar o celular depois de habilitá-lo para o sistema inicializar o recurso. A correção impede registrar falhas como congelamentos bem-sucedidos.
