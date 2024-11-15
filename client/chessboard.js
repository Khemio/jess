const squares = document.querySelectorAll('table.chessboard td');
const gameSocket = new WebSocket("ws://localhost:4220/game");

let currMove = "";
let player;

class Player {
    #role;

    constructor(role) {
        this.role = role;
    }

    getRole() {
        return this.role;
    }
}

function parseMessage(msg) {
    const tokens = msg.split(":");

    if (tokens[0] === "role") {
        const role = tokens[1];
        player = new Player(role);
        document.querySelector("#player").innerText = role[0].toUpperCase() + role.slice(1);
    } else if (tokens[0] === "move") {
        console.log("move");
        movePiece(tokens[1]);
    }
}

function tryMove(position, isValid) {
    // TODO: Handle turns
    if (currMove.length === 0) {
        if (!isValid) {
            console.log("Wrong square");
            // TODO: handle the notification (wrong start position)
            return;
        }
        currMove = position;
    } else {
        currMove += position;
        gameSocket.send(`move:${currMove}`);
        currMove = "";
    }

}

function movePiece(move) {
    const origin = move.slice(0,2);
    const dest = move.slice(2);

    console.log(`origin: ${origin}`)
    console.log(`destination: ${dest}`)

    const originElem = getElement(origin)
    const piece = originElem.dataset.piece;
    originElem.removeAttribute("data-piece");

    getElement(dest).setAttribute("data-piece", piece);

}

function getIndex(element) {
    return Array.from(element.parentNode.children).indexOf(element);
}

function getNotation(element) {
    const col = getIndex(element) - 1;
    const row = getIndex(element.parentNode);
    return 'abcdefgh'.charAt(col) + (8 - row);
}

function getElement(notation) {
    const col = 'abcdefgh'.indexOf(notation[0]) ;
    const row = 8 - notation[1];
    const index = row * 8 + col;

    return squares[index];
}

function isValidStart(element) {
    const data = element.attributes["data-piece"];
    if (Boolean(data)) { 
        return data.value.slice(0,5) === player.getRole();
    }
    return  false;
}

// Event listeners
squares.forEach((element) => {
    element.addEventListener('mouseenter', (event) => {
        const element = event.currentTarget;
        const notation = getNotation(element);
        document.getElementById('currentSquare').innerText = `Current square is ${notation}`;
    });

    element.addEventListener('mouseleave', (event) => {
        document.getElementById('currentSquare').innerText = '';
    });

    element.addEventListener('click', (event) => {
        const isValid = isValidStart(element);
        const position = getNotation(event.target);
        tryMove(position, isValid);

    });
});

gameSocket.onopen = (event) => {
    console.log("Opened")
}

gameSocket.onerror = (error) => {
    console.log('WebSocket Error');
};

gameSocket.onmessage = (event) => {
    // console.log(event);
    const data = event.data;
    parseMessage(data);
}

gameSocket.onclose = (e) => {
    console.log("closed");
    console.log(e);
}


