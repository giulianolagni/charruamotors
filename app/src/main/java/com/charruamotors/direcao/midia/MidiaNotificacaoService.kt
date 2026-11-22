package com.charruamotors.direcao.midia

import android.service.notification.NotificationListenerService

/**
 * Serviço de "Acesso a notificações". O app não lê nem altera notificações: o Android só exige
 * um listener habilitado para que o [ControladorMidia] possa consultar as sessões de mídia de
 * outros apps (`MediaSessionManager.getActiveSessions`).
 */
class MidiaNotificacaoService : NotificationListenerService()
