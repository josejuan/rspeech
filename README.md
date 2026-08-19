# 🎙️ EL INGENIOSO ARTEFACTO «RSPEECH»
### *Tratado y artificio digital para trocar vuestro ingenio móvil en bocal y trompeta virtual de vuestra computadora.*

> *«Sábete, discreto lector, que no hay encantamento ni hechizo de Frestón que iguale a la presteza de la palabra volandera sobre las ondas del éter.»*

---

## 📜 De lo que trata esta sin par invención

En un lugar de la red, de cuya IP no quiero acordarme, plugo a la necesidad y al ingenio urdir esta herramienta llamada **RSpeech**. Mediante aqueste artificio, cualquier andante usuario de **Linux** (bien sea servido por los númenes de *PipeWire* o los de *PulseAudio*) podrá convertir su fono **Android** en fiel escudero sonoro: sirviéndole de **micrófono de solapa** en sus lides y parlamentos, y de **parlante o bocina** donde escuchar las tonadas del ordenador sin demora ni tardanza perceptible.

---

## ⚡ De las singulares grandezas y virtudes del ingenio

* **Vuelo velocísimo por datagramas UDP**: Despáchase la voz en ráfagas breves de diez a veinte milésimas de segundo a **48.000 vaivenes por segundo (48 kHz)**, hermanándose con el *FastMixer* del silicio sin padecer la modorra de los buferes tardos.
* **Gobierno de órganos sonoros virtuales**: El servidor Python engendra e indulta en el sistema operativo sendas tarjetas invisibles (`android_mic` y `android_speaker`), siendo la obra tan **idempotente y templada** que si topa con restos de pasadas refriegas, los allana y limpia sin mudar la paz del reino.
* **Acompasamiento de relojes y castigo a la tardanza**: Mediante justas de *Ping* y *Pong*, discierne el teléfono la hora cabal del servidor. Paquete que osare presentarse con más de **100 milisegundos de retraso** es arrojado al abismo del olvido sin llegar al tímpano, manteniendo la plática fresca como agua de mayo.
* **Modo de interruptor continuo o pulsador de arremetida (*Push-to-Talk*)**: Bien podéis dejar el micro expedito con un conmutador de palanca, o bien mantener oprimido el botón cual lanza en ristre mientras dure vuestro discurso.
* **Perfiles doblados en sendas pestañas**: Dos son las configuraciones que guarda en su memoria, mudables con leve roce del pulgar para trocar de posada o de servidor.
* **Templanza ante el silencio**: Cuando callare el ordenador, ningún datagrama estéril fatiga el aire ni gasta la lumbre de la batería.

---

## 🗺️ Trazado de la andadura

```
+-----------------------------------+             +---------------------------------------+
|        ANDANTE ANDROID            |             |           COMPUTADORA LINUX           |
|                                   |  Datagramas |                                       |
|  [ Micrófono del fono ] --------->| = (48 kHz) >| ---> [ android_mic ]                  |
|                                   |   puerto    |      (Voz que oyen vuestros pares)    |
|  [ Bocina del fono ]    <---------| < (48 kHz) =| <--- [ android_speaker ]              |
|                                   |    14144    |      (Música y sones del sistema)     |
+-----------------------------------+             +---------------------------------------+
```

---

## ⚔️ Instrucciones para armarse caballero

### I. Requisitos en la ínsula del servidor (PC)
Menester es contar con máquina gobernada por **Linux**, dotada de las herramientas de *PulseAudio/PipeWire* (`pactl`, `pacat`, `parec`) y la serpiente **Python 3**.

Despiértese el demonio guardián invocando:
```bash
python3 server_device.py
```

### II. Forja y bendición del artefacto móvil (APK)
Conectado el ingenio por cuerda USB y presto el puente `adb`:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📖 Modo de empleo y gobierno

1. Abriréis la aplicación en vuestro fono y hallaréis el papiro de configuración:
   ```ini
   server.ip=192.168.0.3
   server.port=14144
   user=pepe
   pass=23rc2rc
   audio.rate=48000
   audio.max_latency_ms=100
   ```
2. Pulsad con denuedo **«Guardar y Continuar»**.
3. En la sala de armas:
   * **El Interruptor**: Dejadlo en *ON* si gustáis de charla tendida.
   * **El Pulsador**: Mantened el dedo hincado para soltar vuestras razones breves.
   * **El Oído**: Todo son que enviéis en vuestro Linux hacia la salida `Android_Speaker_Output` sonará con presteza en la palma de vuestra mano.

---

*«Post tenebras spero lucem... y que la latencia no exceda de veinte milisegundos.»*
