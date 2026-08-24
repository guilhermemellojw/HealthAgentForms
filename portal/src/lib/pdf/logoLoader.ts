let logoCache: string | null = null;
let vigilanciaCache: string | null = null;

async function fetchAsBase64(url: string): Promise<string> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Falha ao carregar ${url}: ${res.status}`);
  const blob = await res.blob();
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      const base64 = result.split(",")[1] ?? "";
      resolve(base64);
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

export async function getLogoBase64(): Promise<string> {
  if (logoCache) return logoCache;
  try {
    logoCache = await fetchAsBase64("/logo.png");
    return logoCache;
  } catch {
    const mod = await import("./assets");
    logoCache = mod.LOGO_BASE64;
    return logoCache;
  }
}

export async function getVigilanciaBase64(): Promise<string> {
  if (vigilanciaCache) return vigilanciaCache;
  try {
    vigilanciaCache = await fetchAsBase64("/vigilancia.png");
    return vigilanciaCache;
  } catch {
    const mod = await import("./vigilancia_asset");
    vigilanciaCache = mod.VIGILANCIA_LOGO_BASE64;
    return vigilanciaCache;
  }
}
