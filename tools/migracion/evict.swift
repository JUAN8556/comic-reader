// evict — desaloja la copia local de ficheros de iCloud Drive sin borrarlos.
//
// iCloud conserva el fichero; solo se libera el espacio en la Mac
// (FileManager.evictUbiquitousItem). brctl ya no tiene «evict» en macOS 26.
//
// Uso:
//   evict <ruta>...            desaloja cada ruta
//   evict --estado <ruta>...   solo informa (no cambia nada)
//
// Salida: una línea por ruta, separada por tabuladores:
//   ok      <ruta>
//   error   <ruta>  <mensaje>
//   estado  <ruta>  <ubicuo:si|no>  <descarga:actual|descargado|no_descargado|?>  <bloques>
// Código de salida: 0 si todas fueron bien, 1 si alguna falló, 2 si el uso es incorrecto.
import Foundation

let args = Array(CommandLine.arguments.dropFirst())
if args.isEmpty || args == ["--estado"] {
    FileHandle.standardError.write("uso: evict [--estado] <ruta>...\n".data(using: .utf8)!)
    exit(2)
}
let soloEstado = args.first == "--estado"
let rutas = soloEstado ? Array(args.dropFirst()) : args
let fm = FileManager.default
var fallos = 0

func bloques(_ ruta: String) -> String {
    var st = stat()
    return lstat(ruta, &st) == 0 ? String(st.st_blocks) : "?"
}

for ruta in rutas {
    let url = URL(fileURLWithPath: ruta)
    if soloEstado {
        let claves: Set<URLResourceKey> = [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey]
        do {
            let v = try url.resourceValues(forKeys: claves)
            let ubicuo = (v.isUbiquitousItem ?? false) ? "si" : "no"
            var descarga = "?"
            if let s = v.ubiquitousItemDownloadingStatus {
                switch s {
                case .current: descarga = "actual"
                case .downloaded: descarga = "descargado"
                case .notDownloaded: descarga = "no_descargado"
                default: descarga = s.rawValue
                }
            }
            print("estado\t\(ruta)\t\(ubicuo)\t\(descarga)\t\(bloques(ruta))")
        } catch {
            print("error\t\(ruta)\t\(error.localizedDescription)")
            fallos += 1
        }
        continue
    }
    do {
        try fm.evictUbiquitousItem(at: url)
        print("ok\t\(ruta)")
    } catch {
        print("error\t\(ruta)\t\(error.localizedDescription)")
        fallos += 1
    }
}
exit(fallos == 0 ? 0 : 1)
