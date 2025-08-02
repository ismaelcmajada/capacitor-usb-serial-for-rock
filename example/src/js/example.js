import { UsbSerial } from 'capacitor-usb-serial-for-rock';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    UsbSerial.echo({ value: inputValue })
}
