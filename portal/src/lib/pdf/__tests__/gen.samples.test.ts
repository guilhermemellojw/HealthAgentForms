import { describe, it } from "vitest";
import { writeFileSync, mkdirSync } from "node:fs";
import { downloadRg } from "../rg";
import type { HouseDoc } from "../../types";

// Harness de validação visual: gera PDFs de amostra para comparação com o app.
// Ativar com: GEN_PDF_SAMPLES=1 npx vitest run gen.samples
describe.skipIf(!process.env.GEN_PDF_SAMPLES)("gen pdf samples", () => {
  it("gera RG e Semanal de amostra", async () => {
    const outDir = process.env.GEN_PDF_OUT ?? "/tmp/opencode/samples";
    mkdirSync(outDir, { recursive: true });

    const streets = [
      "Rua Eugênio José Erthal", "Rua Silvio Dias de Oliveira", "Rua João Caniato",
      "Rua Antônio Reis", "Rua Pedro Álvares", "Rua Sete de Setembro",
    ];
    const agents = ["GUILHERME MELLO", "VINÍCIUS CHAVES"];
    const houses: HouseDoc[] = [];
    let order = 0;
    // Semana 02/08 a 08/08/2026 — mesma janela das amostras Android
    for (let day = 2; day <= 8; day++) {
      const data = `${String(day).padStart(2, "0")}-08-2026`;
      for (let i = 0; i < 30; i++) {
        const agent = agents[i % 2];
        houses.push({
          id: `h_${order}`,
          agentUid: agent === agents[0] ? "kMcqmZ6TrhgIHL1IKQa1kwGknLH3" : "5TeeCeNYaIeERAb1dtrNv66l9H53",
          agentName: agent,
          bairro: "SÃO JOSÉ",
          blockNumber: "1",
          blockSequence: "",
          streetName: streets[i % streets.length],
          number: String(10 + i),
          sequence: i % 3,
          complement: 0,
          visitSegment: 0,
          propertyType: ["R", "C", "TB", "PE", "O"][i % 5],
          situation: ["NONE", "F", "REC", "A", "V"][i % 5],
          larvicida: i % 4 === 0 ? 1 : 0,
          eliminados: i % 5,
          listOrder: order++,
          createdAt: 1754000000000 + order * 1000,
          data,
          isSynced: true,
          editedByAdmin: false,
          quarteiraoConcluido: false,
          localidadeConcluida: false,
          context: { municipio: "BOM JARDIM", categoria: "BRR" },
        } as unknown as HouseDoc);
      }
    }

    const rgHouses = houses.filter((h) => h.bairro === "SÃO JOSÉ" && h.blockNumber === "1");
    const { generateRgPdf } = await import("../rg");
    const rgDoc = await generateRgPdf(rgHouses as never, "SÃO JOSÉ", "1", "Bom Jardim", [...new Set(agents)]);
    writeFileSync(`${outDir}/RG_portal.pdf`, Buffer.from(rgDoc.output("arraybuffer")));

    const { generateSemanalPdf } = await import("../semanal");
    const activities: Record<string, string> = {};
    for (let day = 2; day <= 8; day++) activities[`${String(day).padStart(2, "0")}-08-2026`] = "NORMAL";
    const semDoc = await generateSemanalPdf(
      Object.keys(activities),
      houses as never,
      activities,
      "GUILHERME MELLO",
    );
    writeFileSync(`${outDir}/Semanal_portal.pdf`, Buffer.from(semDoc.output("arraybuffer")));

    console.log(`Amostras geradas em ${outDir}`);
  }, 60_000);
});
