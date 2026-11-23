### Fixed — a modem's cell id is read as the train's feed writes it

The train's feed writes a modem's cell id in hexadecimal. The journey reader took it for a decimal number, so most
cell ids were left out and the rest were read as the wrong number. It now reads them in hexadecimal.
