# CP23.2.1 — Regularización de estacionamiento vencido

Backend y contrato municipal: [documentación completa](../../simertpi-backend/docs/checkpoint-23-2-1.md).

Home conserva CP23.2 y distingue ACTIVE verde, ENDING_SOON amarillo, EXPIRED_IN_GRACE rojo, GRACE_EXCEEDED rojo crítico, REGULARIZATION_ALLOWED rojo y MAX_TIME_REACHED rojo.

En gracia: «Tu tiempo de estacionamiento terminó», «Extiende tu tiempo o finaliza tu estacionamiento», acciones según backend. No se muestra duración/límite ni contador de gracia.

Tras gracia sin actuación: «Período de gracia finalizado», «El tiempo permitido ha sido excedido. Un controlador municipal podrá registrar la actuación correspondiente», sin acciones.

Tras registro humano válido: «Regulariza tu estacionamiento», «Se ha registrado la actuación correspondiente», únicamente acciones permitidas por backend. No se muestra observación interna del inspector.

MAX_TIME_REACHED nunca ofrece extensión. Tras actuación válida puede ofrecer Finalizar para indicar salida, conservando el mensaje de mover el vehículo.

Receipt recibe proyección operational del servidor. El reloj local se ancla a evaluatedAt: no decide negocio ni cambia expectedEndAt. Resume y límites temporales refrescan. Una consulta cada 60 s mientras queda bloqueado detecta actuación municipal; timer se pausa en background y cancela en dispose. Offline conserva estado no confirmado y deshabilita acciones.

Cada vencimiento requiere su propia actuación; extender no reinicia startedAt ni máximo continuo. El espacio y vehículo quedan ocupados hasta cierre confirmado. Confeti y pagos CP23.2 permanecen intactos.

No incluye app municipal, CP24, cambios tarifarios, mapa ni despliegue. APK requiere backend actualizado con V32 para prueba física del flujo completo.


## Aclaración final de acumulación

El máximo se decide por tiempo contratado acumulado, no por horas de reloj transcurridas ni por minutos de gracia. Cada vencimiento sigue la misma secuencia: EXPIRED_IN_GRACE, GRACE_EXCEEDED y actuación humana. Después de la actuación: 180 contratados permiten opciones de hasta 60 adicionales; 240 contratados muestran Tiempo máximo alcanzado, sin Extender y con Finalizar. Flutter respeta la proyección backend incluso si el estado persistido histórico es MAX_TIME_REACHED.
